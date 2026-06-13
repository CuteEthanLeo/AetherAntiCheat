"""
AetherAntiCheat — 1D ResNet + Multi-Head Self-Attention CNN Trainer.

Reads CSV training data exported by the plugin, builds a deep 1D-CNN
with residual blocks and self-attention, then exports weights as pure
JSON for the Java CNNInferenceEngine to load.

Usage:
    python cnn_train.py [--data-dir PATH] [--epochs N] [--batch-size N]

Output:
    cnn_model_weights.json  — all layer weights/biases (Java-loadable)
    cnn_model_stats.json    — feature mean/std for input normalization
"""

import argparse
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

# ── Check deps early ────────────────────────────────────────────────────
try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    from torch.utils.data import Dataset, DataLoader
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import classification_report, roc_auc_score
except ImportError as e:
    print(f"[ERROR] Missing dependency: {e}")
    print("Install with: pip install torch>=2.0 numpy>=1.24 scikit-learn>=1.3")
    sys.exit(1)

# ── Constants ───────────────────────────────────────────────────────────
FEATURE_NAMES = [
    "deltaYaw", "deltaPitch", "aimError", "gcdResY", "gcdResP",
    "angVel", "angAccel", "jerk", "atkIntervalMs", "cps",
    "attackerYaw", "attackerPitch", "targetYaw", "targetPitch",
    "yawError", "pitchError", "distanceToTarget", "movementAngle",
    "sprinting", "blocking"
]
N_FEATURES = len(FEATURE_NAMES)  # 20
SEQUENCE_LENGTH = 16   # reduced for small datasets (was 128)
SLIDING_STRIDE = 2    # more sequences from limited data (was 16)
BATCH_SIZE = 32
EPOCHS = 120
LEARNING_RATE = 1e-3
WEIGHT_DECAY = 1e-4
EARLY_STOP_PATIENCE = 15
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


# ══════════════════════════════════════════════════════════════════════════
# Data Loading
# ══════════════════════════════════════════════════════════════════════════

def load_csvs(data_dir: str) -> tuple:
    """Load all CSV files, return (cheat_sequences, normal_sequences)."""
    data_path = Path(data_dir)
    if not data_path.exists():
        raise FileNotFoundError(f"Training directory not found: {data_dir}")

    csv_files = sorted(data_path.glob("*.csv"))
    if not csv_files:
        raise FileNotFoundError(f"No CSV files found in {data_dir}")

    cheat_data = []   # list of (timestamp, features[N_FEATURES])
    normal_data = []
    warned_old = False

    for csv_file in csv_files:
        with open(csv_file, "r") as f:
            header = f.readline()  # skip header
            for line in f:
                parts = line.strip().split(",")
                n_parts = len(parts)
                if n_parts < 13:
                    continue
                label = parts[0].strip().lower()
                if label not in ("cheat", "normal"):
                    continue
                try:
                    timestamp = int(parts[1])
                    if n_parts >= 23:
                        # New format: 20 features at indices 2-21
                        features = [float(parts[i]) for i in range(2, 22)]
                    else:
                        # Old format: 10 features at indices 2-11, zero-pad
                        if not warned_old:
                            print(f"[DATA] Loading old-format CSV (10 features). "
                                  f"New features zero-padded. File: {csv_file.name}")
                            warned_old = True
                        features = [float(parts[i]) for i in range(2, 12)]
                        features.extend([0.0] * 10)  # pad to 20
                except (ValueError, IndexError):
                    continue

                entry = (timestamp, features)
                if label == "cheat":
                    cheat_data.append(entry)
                else:
                    normal_data.append(entry)

    # Sort by timestamp within each class
    cheat_data.sort(key=lambda x: x[0])
    normal_data.sort(key=lambda x: x[0])

    print(f"[DATA] Loaded {len(csv_files)} CSV files")
    print(f"       Cheat samples:  {len(cheat_data)}")
    print(f"       Normal samples: {len(normal_data)}")

    return cheat_data, normal_data


def build_sequences(data: list, seq_len: int, stride: int) -> np.ndarray:
    """Build sliding-window sequences from sorted sample list.

    Args:
        data: list of (timestamp, features[N_FEATURES])
        seq_len: sequence length (128)
        stride: sliding window stride (16)

    Returns:
        ndarray of shape [n_sequences, seq_len, N_FEATURES]
    """
    features_arr = np.array([f for _, f in data], dtype=np.float32)
    n = len(features_arr)
    sequences = []

    for start in range(0, n - seq_len + 1, stride):
        seq = features_arr[start:start + seq_len]
        sequences.append(seq)

    if not sequences:
        # If data is shorter than seq_len, pad a single sequence
        padded = np.zeros((seq_len, N_FEATURES), dtype=np.float32)
        padded[:n] = features_arr
        sequences.append(padded)

    return np.array(sequences, dtype=np.float32)


# ══════════════════════════════════════════════════════════════════════════
# Data Augmentation
# ══════════════════════════════════════════════════════════════════════════

class AugmentedDataset(Dataset):
    """Dataset with online augmentation: noise, time-stretch, dropout, mixup."""

    def __init__(self, sequences, labels, augment=False):
        self.sequences = torch.FloatTensor(sequences)  # [N, 128, N_FEATURES]
        self.labels = torch.FloatTensor(labels).unsqueeze(1)  # [N, 1]
        self.augment = augment

    def __len__(self):
        return len(self.sequences)

    def __getitem__(self, idx):
        seq = self.sequences[idx].clone()
        label = self.labels[idx]

        if self.augment:
            seq = self._augment(seq)

        return seq, label

    def _augment(self, seq):
        """Apply random augmentations to a single sequence."""
        # 1. Gaussian noise on rotation features (indices 0-3 + 10-15: deltaYaw/Pitch, aimError,
        #    gcdRes, attackerYaw/Pitch, targetYaw/Pitch, yawError/pitchError)
        if torch.rand(1).item() < 0.5:
            # Old rotation features (0-3)
            noise1 = torch.randn_like(seq[:, :4]) * 0.05
            seq[:, :4] += noise1
            # New rotation/vector features (10-15)
            if seq.shape[1] >= 16:
                rot_indices = list(range(10, 16))
                noise2 = torch.randn_like(seq[:, rot_indices]) * 0.05
                seq[:, rot_indices] += noise2

        # 2. Time stretch (scale along sequence length via linear interpolation)
        if torch.rand(1).item() < 0.4:
            scale = 0.8 + torch.rand(1).item() * 0.4  # 0.8x - 1.2x
            seq = self._time_stretch(seq, scale)

        # 3. Random feature dropout (mask 10-20% of time steps)
        if torch.rand(1).item() < 0.4:
            mask_ratio = 0.1 + torch.rand(1).item() * 0.1
            mask = torch.rand(seq.shape[0], 1) > mask_ratio
            seq = seq * mask.float()

        # 4. Feature-wise noise on timing features
        if torch.rand(1).item() < 0.3:
            noise = torch.randn(seq.shape[0]) * 0.02 * seq[:, -2].std()  # atkInterval noise
            seq[:, -2] += noise  # atkIntervalMs

        return seq

    @staticmethod
    def _time_stretch(seq, scale):
        """Stretch/squeeze sequence along time axis via linear interpolation."""
        orig_len = seq.shape[0]
        new_len = max(16, int(orig_len * scale))
        indices = torch.linspace(0, orig_len - 1, new_len)
        stretched = torch.zeros(orig_len, seq.shape[1])

        for f in range(seq.shape[1]):
            stretched[:, f] = torch.nn.functional.interpolate(
                seq[:, f].unsqueeze(0).unsqueeze(0),
                size=orig_len, mode='linear', align_corners=True
            ).squeeze()

        return stretched


# ══════════════════════════════════════════════════════════════════════════
# Model Architecture — 1D ResNet + MultiHead Self-Attention
# ══════════════════════════════════════════════════════════════════════════

class LayerNorm1D(nn.Module):
    """LayerNorm over the feature dimension for 3D input [B, T, F]."""
    def __init__(self, normalized_shape):
        super().__init__()
        self.ln = nn.LayerNorm(normalized_shape)

    def forward(self, x):
        # x: [B, T, F] -> LayerNorm over last dim
        return self.ln(x)


class ConvBlock(nn.Module):
    """Conv1D → BatchNorm1D → ReLU."""
    def __init__(self, in_ch, out_ch, kernel_size, stride=1, padding=0):
        super().__init__()
        self.conv = nn.Conv1d(in_ch, out_ch, kernel_size, stride, padding, bias=False)
        self.bn = nn.BatchNorm1d(out_ch)
        self.relu = nn.ReLU(inplace=True)

    def forward(self, x):
        # x: [B, C, T]
        return self.relu(self.bn(self.conv(x)))


class ResBlock1D(nn.Module):
    """Residual block with optional projection shortcut."""
    def __init__(self, in_ch, out_ch, kernel_size=3, stride=1):
        super().__init__()
        self.conv1 = nn.Conv1d(in_ch, out_ch, kernel_size, stride,
                               padding=kernel_size // 2, bias=False)
        self.bn1 = nn.BatchNorm1d(out_ch)
        self.conv2 = nn.Conv1d(out_ch, out_ch, kernel_size,
                               padding=kernel_size // 2, bias=False)
        self.bn2 = nn.BatchNorm1d(out_ch)
        self.relu = nn.ReLU(inplace=True)

        # Projection shortcut if dimensions change
        self.proj = None
        if in_ch != out_ch or stride != 1:
            self.proj = nn.Sequential(
                nn.Conv1d(in_ch, out_ch, 1, stride, bias=False),
                nn.BatchNorm1d(out_ch)
            )

    def forward(self, x):
        identity = x
        out = self.relu(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        if self.proj is not None:
            identity = self.proj(x)
        out = self.relu(out + identity)
        return out


class MultiHeadSelfAttention1D(nn.Module):
    """Multi-head self-attention over the time dimension."""
    def __init__(self, dim, num_heads=8, dropout=0.1):
        super().__init__()
        assert dim % num_heads == 0
        self.dim = dim
        self.num_heads = num_heads
        self.head_dim = dim // num_heads
        self.scale = self.head_dim ** -0.5

        self.qkv = nn.Linear(dim, dim * 3, bias=False)
        self.out_proj = nn.Linear(dim, dim)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x):
        # x: [B, T, D] where D = dim
        B, T, D = x.shape
        qkv = self.qkv(x).reshape(B, T, 3, self.num_heads, self.head_dim)
        qkv = qkv.permute(2, 0, 3, 1, 4)  # [3, B, heads, T, head_dim]
        q, k, v = qkv[0], qkv[1], qkv[2]

        attn = (q @ k.transpose(-2, -1)) * self.scale
        attn = torch.softmax(attn, dim=-1)
        attn = self.dropout(attn)

        out = attn @ v  # [B, heads, T, head_dim]
        out = out.transpose(1, 2).reshape(B, T, D)
        return self.out_proj(out)


class KillAuraCNN(nn.Module):
    """Lightweight 1D-CNN for Aimbot Detection — small enough for Java JSON loading.

    Input:  [B, T, 20]  — T consecutive attacks × 20 features
    Output: [B, 1]      — cheat probability [0, 1]
    """

    def __init__(self, seq_len=16, n_features=20, dropout=0.4):
        super().__init__()

        # Input normalization
        self.input_norm = LayerNorm1D(n_features)

        # ── Conv Block (20 → 32) ─────────────────────────────────
        self.conv1 = ConvBlock(n_features, 32, kernel_size=5, padding=2)
        # → [B, 32, T]

        # ── ResBlock 1 (32 → 32) ─────────────────────────────────
        self.res1_1 = ResBlock1D(32, 32)
        self.pool1 = nn.MaxPool1d(2)  # → [B, 32, T/2]

        # ── ResBlock 2 (32 → 64) ─────────────────────────────────
        self.res2_1 = ResBlock1D(32, 64)
        self.pool2 = nn.MaxPool1d(2)  # → [B, 64, T/4]

        # ── ResBlock 3 (64 → 64) ─────────────────────────────────
        self.res3_1 = ResBlock1D(64, 64)

        # ── Classification head ───────────────────────────────────
        self.global_pool = nn.AdaptiveAvgPool1d(1)  # → [B, 64, 1]
        self.dropout1 = nn.Dropout(dropout)
        self.fc1 = nn.Linear(64, 32)
        self.fc2 = nn.Linear(32, 1)

    def forward(self, x):
        # x: [B, T, F]
        x = self.input_norm(x)
        x = x.transpose(1, 2)  # → [B, F, T] for Conv1d

        x = self.conv1(x)          # [B, 32, T]
        x = self.res1_1(x)         # [B, 32, T]
        x = self.pool1(x)          # [B, 32, T/2]

        x = self.res2_1(x)         # [B, 64, T/2]
        x = self.pool2(x)          # [B, 64, T/4]

        x = self.res3_1(x)         # [B, 64, T/4]

        # Pool + Classify
        x = self.global_pool(x).squeeze(-1)  # → [B, 64]
        x = self.dropout1(F.relu(self.fc1(x)))  # → [B, 32]
        x = torch.sigmoid(self.fc2(x))          # → [B, 1]

        return x

    def count_params(self):
        return sum(p.numel() for p in self.parameters() if p.requires_grad)


# ══════════════════════════════════════════════════════════════════════════
# Weight Export (to JSON for Java CNNInferenceEngine)
# ══════════════════════════════════════════════════════════════════════════

def export_weights(model, stats, output_path):
    """Export all model weights and biases to a JSON file consumable by Java."""
    weights = {}
    state = model.state_dict()

    # Helper: convert tensor → nested list
    def t2l(t):
        return t.detach().cpu().tolist()

    # Input LayerNorm
    weights["input_norm.gamma"] = t2l(state["input_norm.ln.weight"])
    weights["input_norm.beta"]  = t2l(state["input_norm.ln.bias"])

    # Conv1
    weights["conv1.conv.weight"] = t2l(state["conv1.conv.weight"])
    weights["conv1.bn.weight"]   = t2l(state["conv1.bn.weight"])
    weights["conv1.bn.bias"]     = t2l(state["conv1.bn.bias"])
    weights["conv1.bn.running_mean"] = t2l(state["conv1.bn.running_mean"])
    weights["conv1.bn.running_var"]  = t2l(state["conv1.bn.running_var"])

    # ResBlocks (only res1_1, res2_1, res3_1 in simplified model)
    for block_name in ["res1_1", "res2_1", "res3_1"]:
        for layer in ["conv1", "bn1", "conv2", "bn2"]:
            for param in ["weight", "bias"]:
                key = f"{block_name}.{layer}.{param}"
                if key in state:
                    weights[f"{block_name}.{layer}.{param}"] = t2l(state[key])
            if layer.startswith("bn"):
                for stat_name in ["running_mean", "running_var"]:
                    key = f"{block_name}.{layer}.{stat_name}"
                    if key in state:
                        weights[key] = t2l(state[key])
        # Projection shortcut (only res2_1 has it: 32→64)
        for proj_layer in ["proj.0.weight", "proj.1.weight", "proj.1.bias",
                           "proj.1.running_mean", "proj.1.running_var"]:
            key = f"{block_name}.{proj_layer}"
            if key in state:
                weights[key] = t2l(state[key])

    # FC layers (only fc1, fc2 in simplified model)
    for fc_name in ["fc1", "fc2"]:
        weights[f"{fc_name}.weight"] = t2l(state[f"{fc_name}.weight"])
        weights[f"{fc_name}.bias"]   = t2l(state[f"{fc_name}.bias"])

    # Metadata
    output = {
        "meta": {
            "architecture": "ResNet1D-Lightweight",
            "sequence_length": SEQUENCE_LENGTH,
            "n_features": N_FEATURES,
            "feature_names": FEATURE_NAMES,
            "total_params": model.count_params(),
        },
        "weights": weights,
    }

    with open(output_path, "w") as f:
        json.dump(output, f, separators=(",", ":"))

    file_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"[EXPORT] Weights saved to: {output_path} ({file_size:.1f} MB)")


def export_stats(mean, std, output_path):
    """Export feature normalization stats to JSON."""
    stats = {
        "feature_names": FEATURE_NAMES,
        "mean": mean.tolist() if isinstance(mean, np.ndarray) else mean,
        "std": std.tolist() if isinstance(std, np.ndarray) else std,
    }
    with open(output_path, "w") as f:
        json.dump(stats, f, indent=2)
    print(f"[EXPORT] Stats saved to: {output_path}")


# ══════════════════════════════════════════════════════════════════════════
# Training
# ══════════════════════════════════════════════════════════════════════════

def compute_class_weights(cheat_count, normal_count):
    """Compute inverse-frequency class weights for balanced loss."""
    total = cheat_count + normal_count
    w_cheat = total / (2 * cheat_count) if cheat_count > 0 else 1.0
    w_normal = total / (2 * normal_count) if normal_count > 0 else 1.0
    print(f"[BALANCE] Class weights — cheat: {w_cheat:.3f}, normal: {w_normal:.3f}")
    return torch.FloatTensor([w_normal])  # pos_weight for BCELoss (weight for positive=cheat)


def train_epoch(model, loader, optimizer, criterion, device):
    model.train()
    total_loss = 0.0
    correct = 0
    total = 0

    for seq, label in loader:
        seq, label = seq.to(device), label.to(device)
        optimizer.zero_grad()
        output = model(seq)
        loss = criterion(output, label)
        loss.backward()
        optimizer.step()

        total_loss += loss.item() * seq.size(0)
        pred = (output > 0.5).float()
        correct += (pred == label).sum().item()
        total += seq.size(0)

    return total_loss / total, correct / total


@torch.no_grad()
def validate(model, loader, criterion, device):
    model.eval()
    total_loss = 0.0
    correct = 0
    total = 0
    all_preds = []
    all_labels = []

    for seq, label in loader:
        seq, label = seq.to(device), label.to(device)
        output = model(seq)
        loss = criterion(output, label)

        total_loss += loss.item() * seq.size(0)
        pred = (output > 0.5).float()
        correct += (pred == label).sum().item()
        total += seq.size(0)

        all_preds.extend(output.cpu().numpy().flatten())
        all_labels.extend(label.cpu().numpy().flatten())

    auc = roc_auc_score(all_labels, all_preds) if len(set(all_labels)) > 1 else 0.5
    return total_loss / total, correct / total, auc


def main():
    parser = argparse.ArgumentParser(description="AetherAntiCheat CNN Trainer")
    parser.add_argument("--data-dir", default="../../plugins/AetherAntiCheat/training",
                        help="Path to directory containing CSV training files")
    parser.add_argument("--epochs", type=int, default=EPOCHS, help="Max training epochs")
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE, help="Batch size")
    parser.add_argument("--lr", type=float, default=LEARNING_RATE, help="Learning rate")
    parser.add_argument("--output-dir", default=".", help="Output directory for model files")
    args = parser.parse_args()

    print("=" * 60)
    print("AetherAntiCheat — CNN Trainer")
    print(f"Device: {DEVICE}")
    print(f"Architecture: ResNet1D + 8-Head Self-Attention")
    print(f"Input: [{SEQUENCE_LENGTH} × {N_FEATURES}] features")
    print("=" * 60)

    # ── Load data ──────────────────────────────────────────────────
    cheat_data, normal_data = load_csvs(args.data_dir)

    if len(cheat_data) < 10 or len(normal_data) < 10:
        print("[ERROR] Need at least 10 samples per class. "
              f"Got {len(cheat_data)} cheat, {len(normal_data)} normal.")
        sys.exit(1)

    # Build sequences
    cheat_seqs = build_sequences(cheat_data, SEQUENCE_LENGTH, SLIDING_STRIDE)
    normal_seqs = build_sequences(normal_data, SEQUENCE_LENGTH, SLIDING_STRIDE)

    print(f"[SEQ] Cheat sequences:  {cheat_seqs.shape}")
    print(f"[SEQ] Normal sequences: {normal_seqs.shape}")

    # Create labels
    X = np.concatenate([cheat_seqs, normal_seqs], axis=0)
    y = np.concatenate([
        np.ones(len(cheat_seqs)),
        np.zeros(len(normal_seqs))
    ])

    print(f"[DATA] Total sequences: {len(X)} ({len(cheat_seqs)} cheat, {len(normal_seqs)} normal)")

    # ── Normalize ──────────────────────────────────────────────────
    # Compute per-feature mean/std from TRAINING data only
    X_flat = X.reshape(-1, N_FEATURES)
    feat_mean = X_flat.mean(axis=0)
    feat_std = X_flat.std(axis=0)
    feat_std[feat_std < 1e-8] = 1.0  # avoid div by zero

    X_norm = (X - feat_mean[None, None, :]) / feat_std[None, None, :]

    print(f"[NORM] Feature means: {feat_mean}")
    print(f"[NORM] Feature stds:  {feat_std}")

    # ── Train/Val split ────────────────────────────────────────────
    try:
        X_train, X_val, y_train, y_val = train_test_split(
            X_norm, y, test_size=0.2, random_state=42, stratify=y
        )
    except ValueError:
        print("[WARN] Cannot stratify (too few samples). Using simple split.")
        X_train, X_val, y_train, y_val = train_test_split(
            X_norm, y, test_size=0.2, random_state=42
        )

    train_dataset = AugmentedDataset(X_train, y_train, augment=True)
    val_dataset = AugmentedDataset(X_val, y_val, augment=False)

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size,
                              shuffle=True, drop_last=False)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size, shuffle=False)

    # ── Model, Loss, Optimizer ─────────────────────────────────────
    model = KillAuraCNN(seq_len=SEQUENCE_LENGTH, n_features=N_FEATURES).to(DEVICE)
    print(f"[MODEL] Total parameters: {model.count_params():,}")

    pos_weight = compute_class_weights(len(cheat_seqs), len(normal_seqs)).to(DEVICE)
    criterion = nn.BCELoss()  # Simple BCE (class imbalance handled by pos_weight via sampler)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=WEIGHT_DECAY)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode="max", factor=0.5, patience=8
    )

    # ── Training loop ──────────────────────────────────────────────
    best_val_auc = 0.0
    best_epoch = 0
    no_improve = 0
    best_state = None

    for epoch in range(1, args.epochs + 1):
        train_loss, train_acc = train_epoch(model, train_loader, optimizer, criterion, DEVICE)
        val_loss, val_acc, val_auc = validate(model, val_loader, criterion, DEVICE)

        scheduler.step(val_auc)

        print(f"Epoch {epoch:3d}/{args.epochs} | "
              f"Train Loss: {train_loss:.4f} Acc: {train_acc:.3f} | "
              f"Val Loss: {val_loss:.4f} Acc: {val_acc:.3f} AUC: {val_auc:.4f}")

        if val_auc > best_val_auc:
            best_val_auc = val_auc
            best_epoch = epoch
            no_improve = 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            no_improve += 1

        if no_improve >= EARLY_STOP_PATIENCE:
            print(f"[STOP] No improvement for {EARLY_STOP_PATIENCE} epochs. "
                  f"Best AUC: {best_val_auc:.4f} at epoch {best_epoch}")
            break

    # ── Restore best model ─────────────────────────────────────────
    model.load_state_dict(best_state)
    print(f"[BEST] Restored model from epoch {best_epoch} (AUC={best_val_auc:.4f})")

    # ── Final evaluation ───────────────────────────────────────────
    val_loss, val_acc, val_auc = validate(model, val_loader, criterion, DEVICE)
    print(f"\n[FINAL] Val Loss: {val_loss:.4f} Acc: {val_acc:.4f} AUC: {val_auc:.4f}")

    # Classification report
    model.eval()
    all_preds, all_labels = [], []
    with torch.no_grad():
        for seq, label in val_loader:
            seq = seq.to(DEVICE)
            output = model(seq).cpu().numpy().flatten()
            all_preds.extend(output)
            all_labels.extend(label.numpy().flatten())

    pred_binary = (np.array(all_preds) > 0.5).astype(int)
    print("\n" + classification_report(all_labels, pred_binary,
                                       target_names=["Normal", "Cheat"]))

    # ── Export ─────────────────────────────────────────────────────
    os.makedirs(args.output_dir, exist_ok=True)

    weights_path = os.path.join(args.output_dir, "cnn_model_weights.json")
    stats_path = os.path.join(args.output_dir, "cnn_model_stats.json")

    export_weights(model, {"mean": feat_mean, "std": feat_std}, weights_path)
    export_stats(feat_mean, feat_std, stats_path)

    print("\n[DONE] Training complete. Copy these files to your plugin data folder:")
    print(f"       1. {weights_path}")
    print(f"       2. {stats_path}")
    print(f"\n   Then run /aac kaai reload-cnn in-game to load the model.")


if __name__ == "__main__":
    main()

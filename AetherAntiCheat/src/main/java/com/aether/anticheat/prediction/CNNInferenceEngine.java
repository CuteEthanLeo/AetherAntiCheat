package com.aether.anticheat.prediction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Pure-Java CNN forward-pass engine for KillAuraAI.
 * Loads weights from {@code cnn_model_weights.json} exported by cnn_train.py.
 *
 * <h3>Architecture (Lightweight)</h3>
 * <pre>
 *   Input [T, 20] → LayerNorm → Conv1D(32,k=5) → BN → ReLU
 *   → ResBlock(32→32) → MaxPool → ResBlock(32→64) → MaxPool
 *   → ResBlock(64→64) → GlobalAvgPool → FC(32) → Sigmoid → [0,1]
 * </pre>
 */
public class CNNInferenceEngine {

    private static final int N_FEATURES = 20;
    private static final float EPS = 1e-5f;

    private final Logger logger;
    private boolean loaded;
    private int totalParams;

    // ── Layer weights ────────────────────────────────────────────────
    private float[] lnGamma, lnBeta;          // LayerNorm [20]

    // Conv1 block (20→32, k=5)
    private float[][][] conv1Weight;           // [32][20][5]
    private float[] conv1BnGamma, conv1BnBeta, conv1BnMean, conv1BnVar;

    // ResBlock 1 (32→32, k=3)
    private float[][][] res1_1Conv1W, res1_1Conv2W;  // [32][32][3]
    private float[] res1_1Bn1G, res1_1Bn1B, res1_1Bn1M, res1_1Bn1V;
    private float[] res1_1Bn2G, res1_1Bn2B, res1_1Bn2M, res1_1Bn2V;

    // ResBlock 2 (32→64, k=3) — has projection
    private float[][][] res2_1Conv1W;          // [64][32][3]
    private float[] res2_1Bn1G, res2_1Bn1B, res2_1Bn1M, res2_1Bn1V;
    private float[][][] res2_1Conv2W;          // [64][64][3]
    private float[] res2_1Bn2G, res2_1Bn2B, res2_1Bn2M, res2_1Bn2V;
    private float[][] res2_1ProjW;             // [64][32] 1x1 conv
    private float[] res2_1ProjBnG, res2_1ProjBnB, res2_1ProjBnM, res2_1ProjBnV;

    // ResBlock 3 (64→64, k=3)
    private float[][][] res3_1Conv1W, res3_1Conv2W;  // [64][64][3]
    private float[] res3_1Bn1G, res3_1Bn1B, res3_1Bn1M, res3_1Bn1V;
    private float[] res3_1Bn2G, res3_1Bn2B, res3_1Bn2M, res3_1Bn2V;

    // FC layers
    private float[][] fc1Weight;  // [32][64]
    private float[] fc1Bias;      // [32]
    private float[][] fc2Weight;  // [1][32]
    private float[] fc2Bias;      // [1]

    // Normalization stats
    private float[] featMean, featStd;  // [20]

    public CNNInferenceEngine(Logger logger) {
        this.logger = logger;
    }

    // ══════════════════════════════════════════════════════════════════
    // Loading
    // ══════════════════════════════════════════════════════════════════

    public boolean loadModel(File weightsFile, File statsFile) {
        try {
            JsonObject root;
            try (FileReader fr = new FileReader(weightsFile)) {
                root = new JsonParser().parse(fr).getAsJsonObject();
            }

            JsonObject meta = root.getAsJsonObject("meta");
            totalParams = meta.get("total_params").getAsInt();
            JsonObject w = root.getAsJsonObject("weights");

            // Input LayerNorm
            lnGamma = j1d(w.getAsJsonArray("input_norm.gamma"));
            lnBeta  = j1d(w.getAsJsonArray("input_norm.beta"));

            // Conv1
            conv1Weight  = j3d(w.getAsJsonArray("conv1.conv.weight"), 32, 20, 5);
            conv1BnGamma = j1d(w.getAsJsonArray("conv1.bn.weight"));
            conv1BnBeta  = j1d(w.getAsJsonArray("conv1.bn.bias"));
            conv1BnMean  = j1d(w.getAsJsonArray("conv1.bn.running_mean"));
            conv1BnVar   = j1d(w.getAsJsonArray("conv1.bn.running_var"));

            // ResBlock 1.1 (32→32)
            res1_1Conv1W = j3d(w.getAsJsonArray("res1_1.conv1.weight"), 32, 32, 3);
            res1_1Bn1G = j1d(w.getAsJsonArray("res1_1.bn1.weight")); res1_1Bn1B = j1d(w.getAsJsonArray("res1_1.bn1.bias"));
            res1_1Bn1M = j1d(w.getAsJsonArray("res1_1.bn1.running_mean")); res1_1Bn1V = j1d(w.getAsJsonArray("res1_1.bn1.running_var"));
            res1_1Conv2W = j3d(w.getAsJsonArray("res1_1.conv2.weight"), 32, 32, 3);
            res1_1Bn2G = j1d(w.getAsJsonArray("res1_1.bn2.weight")); res1_1Bn2B = j1d(w.getAsJsonArray("res1_1.bn2.bias"));
            res1_1Bn2M = j1d(w.getAsJsonArray("res1_1.bn2.running_mean")); res1_1Bn2V = j1d(w.getAsJsonArray("res1_1.bn2.running_var"));

            // ResBlock 2.1 (32→64 with proj)
            res2_1Conv1W = j3d(w.getAsJsonArray("res2_1.conv1.weight"), 64, 32, 3);
            res2_1Bn1G = j1d(w.getAsJsonArray("res2_1.bn1.weight")); res2_1Bn1B = j1d(w.getAsJsonArray("res2_1.bn1.bias"));
            res2_1Bn1M = j1d(w.getAsJsonArray("res2_1.bn1.running_mean")); res2_1Bn1V = j1d(w.getAsJsonArray("res2_1.bn1.running_var"));
            res2_1Conv2W = j3d(w.getAsJsonArray("res2_1.conv2.weight"), 64, 64, 3);
            res2_1Bn2G = j1d(w.getAsJsonArray("res2_1.bn2.weight")); res2_1Bn2B = j1d(w.getAsJsonArray("res2_1.bn2.bias"));
            res2_1Bn2M = j1d(w.getAsJsonArray("res2_1.bn2.running_mean")); res2_1Bn2V = j1d(w.getAsJsonArray("res2_1.bn2.running_var"));
            res2_1ProjW = j2d(w.getAsJsonArray("res2_1.proj.0.weight"), 64, 32);
            res2_1ProjBnG = j1d(w.getAsJsonArray("res2_1.proj.1.weight")); res2_1ProjBnB = j1d(w.getAsJsonArray("res2_1.proj.1.bias"));
            res2_1ProjBnM = j1d(w.getAsJsonArray("res2_1.proj.1.running_mean")); res2_1ProjBnV = j1d(w.getAsJsonArray("res2_1.proj.1.running_var"));

            // ResBlock 3.1 (64→64)
            res3_1Conv1W = j3d(w.getAsJsonArray("res3_1.conv1.weight"), 64, 64, 3);
            res3_1Bn1G = j1d(w.getAsJsonArray("res3_1.bn1.weight")); res3_1Bn1B = j1d(w.getAsJsonArray("res3_1.bn1.bias"));
            res3_1Bn1M = j1d(w.getAsJsonArray("res3_1.bn1.running_mean")); res3_1Bn1V = j1d(w.getAsJsonArray("res3_1.bn1.running_var"));
            res3_1Conv2W = j3d(w.getAsJsonArray("res3_1.conv2.weight"), 64, 64, 3);
            res3_1Bn2G = j1d(w.getAsJsonArray("res3_1.bn2.weight")); res3_1Bn2B = j1d(w.getAsJsonArray("res3_1.bn2.bias"));
            res3_1Bn2M = j1d(w.getAsJsonArray("res3_1.bn2.running_mean")); res3_1Bn2V = j1d(w.getAsJsonArray("res3_1.bn2.running_var"));

            // FC
            fc1Weight = j2d(w.getAsJsonArray("fc1.weight"), 32, 64);
            fc1Bias   = j1d(w.getAsJsonArray("fc1.bias"));
            fc2Weight = j2d(w.getAsJsonArray("fc2.weight"), 1, 32);
            fc2Bias   = j1d(w.getAsJsonArray("fc2.bias"));

            // Normalization stats
            try (FileReader fr = new FileReader(statsFile)) {
                JsonObject stats = new JsonParser().parse(fr).getAsJsonObject();
                featMean = j1d(stats.getAsJsonArray("mean"));
                featStd  = j1d(stats.getAsJsonArray("std"));
                for (int i = 0; i < featStd.length; i++) {
                    if (featStd[i] < 1e-8f) featStd[i] = 1.0f;
                }
            }

            loaded = true;
            int kb = (int)(weightsFile.length() / 1024);
            logger.info("[CNNEngine] Model loaded — " + totalParams + " params, " + kb + " KB");
            return true;

        } catch (IOException e) {
            logger.warning("[CNNEngine] Failed to load: " + e.getMessage());
            return false;
        }
    }

    public boolean isLoaded() { return loaded; }
    public int getTotalParams() { return totalParams; }

    // ══════════════════════════════════════════════════════════════════
    // Inference
    // ══════════════════════════════════════════════════════════════════

    public float predict(float[][] input) {
        float[][] x = normalize(input);                     // [T][10]
        layerNorm2D(x, lnGamma, lnBeta);                    // [T][10]

        float[][] c1 = conv1dPad(x, conv1Weight, 5, 2);     // [T][32]
        batchNorm2D(c1, conv1BnGamma, conv1BnBeta, conv1BnMean, conv1BnVar);
        relu2D(c1);

        float[][] r1 = resBlock(c1, res1_1Conv1W, res1_1Bn1G, res1_1Bn1B, res1_1Bn1M, res1_1Bn1V,
                res1_1Conv2W, res1_1Bn2G, res1_1Bn2B, res1_1Bn2M, res1_1Bn2V, null, null, null, null, null);
        float[][] p1 = maxPool1D(r1, 2);

        float[][] r2 = resBlock(p1, res2_1Conv1W, res2_1Bn1G, res2_1Bn1B, res2_1Bn1M, res2_1Bn1V,
                res2_1Conv2W, res2_1Bn2G, res2_1Bn2B, res2_1Bn2M, res2_1Bn2V,
                res2_1ProjW, res2_1ProjBnG, res2_1ProjBnB, res2_1ProjBnM, res2_1ProjBnV);
        float[][] p2 = maxPool1D(r2, 2);

        float[][] r3 = resBlock(p2, res3_1Conv1W, res3_1Bn1G, res3_1Bn1B, res3_1Bn1M, res3_1Bn1V,
                res3_1Conv2W, res3_1Bn2G, res3_1Bn2B, res3_1Bn2M, res3_1Bn2V, null, null, null, null, null);

        float[] g = globalAvgPool(r3);                     // [64]
        float[] d1 = denseRelu(g, fc1Weight, fc1Bias);     // [32]
        float logit = dot(d1, fc2Weight[0]) + fc2Bias[0];
        return sigmoid(logit);
    }

    // ══════════════════════════════════════════════════════════════════
    // Layer ops
    // ══════════════════════════════════════════════════════════════════

    private float[][] normalize(float[][] input) {
        int T = input.length;
        float[][] out = new float[T][N_FEATURES];
        for (int t = 0; t < T; t++)
            for (int f = 0; f < N_FEATURES; f++)
                out[t][f] = (input[t][f] - featMean[f]) / featStd[f];
        return out;
    }

    private void layerNorm2D(float[][] x, float[] gamma, float[] beta) {
        int T = x.length, F = x[0].length;
        for (int t = 0; t < T; t++) {
            float sum = 0;
            for (int f = 0; f < F; f++) sum += x[t][f];
            float mean = sum / F;
            float sq = 0;
            for (int f = 0; f < F; f++) { float d = x[t][f] - mean; sq += d * d; }
            float std = (float) Math.sqrt(sq / F + EPS);
            for (int f = 0; f < F; f++)
                x[t][f] = gamma[f] * (x[t][f] - mean) / std + beta[f];
        }
    }

    private float[][] conv1dPad(float[][] input, float[][][] weight, int k, int pad) {
        int T = input.length, inC = weight[0].length, outC = weight.length;
        float[][] out = new float[T][outC];
        for (int oc = 0; oc < outC; oc++) {
            for (int t = 0; t < T; t++) {
                float sum = 0;
                for (int ki = 0; ki < k; ki++) {
                    int idx = t + ki - pad;
                    if (idx >= 0 && idx < T)
                        for (int ic = 0; ic < inC; ic++)
                            sum += input[idx][ic] * weight[oc][ic][ki];
                }
                out[t][oc] = sum;
            }
        }
        return out;
    }

    private void batchNorm2D(float[][] x, float[] g, float[] b, float[] m, float[] v) {
        for (float[] row : x)
            for (int c = 0; c < g.length; c++)
                row[c] = g[c] * (row[c] - m[c]) / (float) Math.sqrt(v[c] + EPS) + b[c];
    }

    private void relu2D(float[][] x) {
        for (float[] row : x)
            for (int i = 0; i < row.length; i++)
                if (row[i] < 0) row[i] = 0;
    }

    private float[][] maxPool1D(float[][] x, int pool) {
        int T = x.length, C = x[0].length, To = T / pool;
        float[][] out = new float[To][C];
        for (int t = 0; t < To; t++)
            for (int c = 0; c < C; c++) {
                float max = -Float.MAX_VALUE;
                for (int p = 0; p < pool; p++) {
                    float v = x[t * pool + p][c];
                    if (v > max) max = v;
                }
                out[t][c] = max;
            }
        return out;
    }

    /** ResBlock: conv1→bn1→relu→conv2→bn2, shortcut(+proj)→add→relu */
    private float[][] resBlock(float[][] x,
                               float[][][] c1w, float[] bn1g, float[] bn1b, float[] bn1m, float[] bn1v,
                               float[][][] c2w, float[] bn2g, float[] bn2b, float[] bn2m, float[] bn2v,
                               float[][] projW, float[] pG, float[] pB, float[] pM, float[] pV) {
        int T = x.length;
        float[][] path = conv1dPad(x, c1w, 3, 1);
        batchNorm2D(path, bn1g, bn1b, bn1m, bn1v);
        relu2D(path);
        path = conv1dPad(path, c2w, 3, 1);
        batchNorm2D(path, bn2g, bn2b, bn2m, bn2v);

        float[][] shortcut;
        if (projW != null) {
            shortcut = conv1d1x1(x, projW);
            batchNorm2D(shortcut, pG, pB, pM, pV);
        } else {
            shortcut = x;
        }

        int outC = path[0].length;
        float[][] out = new float[T][outC];
        for (int t = 0; t < T; t++)
            for (int c = 0; c < outC; c++)
                out[t][c] = Math.max(0, path[t][c] + shortcut[t][c]);
        return out;
    }

    private float[][] conv1d1x1(float[][] x, float[][] weight) {
        int T = x.length, inC = weight[0].length, outC = weight.length;
        float[][] out = new float[T][outC];
        for (int t = 0; t < T; t++)
            for (int oc = 0; oc < outC; oc++) {
                float sum = 0;
                for (int ic = 0; ic < inC; ic++)
                    sum += x[t][ic] * weight[oc][ic];
                out[t][oc] = sum;
            }
        return out;
    }

    private float[] globalAvgPool(float[][] x) {
        int T = x.length, C = x[0].length;
        float[] out = new float[C];
        for (int c = 0; c < C; c++) {
            float sum = 0;
            for (int t = 0; t < T; t++) sum += x[t][c];
            out[c] = sum / T;
        }
        return out;
    }

    private float[] denseRelu(float[] x, float[][] w, float[] b) {
        float[] out = new float[w.length];
        for (int o = 0; o < w.length; o++) {
            float sum = b[o];
            for (int i = 0; i < x.length; i++) sum += x[i] * w[o][i];
            out[o] = Math.max(0, sum);
        }
        return out;
    }

    private float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private float sigmoid(float x) { return 1.0f / (1.0f + (float) Math.exp(-x)); }

    // ══════════════════════════════════════════════════════════════════
    // JSON helpers
    // ══════════════════════════════════════════════════════════════════

    private static float[] j1d(JsonArray a) {
        float[] o = new float[a.size()];
        for (int i = 0; i < a.size(); i++) o[i] = a.get(i).getAsFloat();
        return o;
    }
    private static float[][] j2d(JsonArray a, int r, int c) {
        float[][] o = new float[r][c];
        for (int i = 0; i < r; i++) { JsonArray row = a.get(i).getAsJsonArray();
            for (int j = 0; j < c; j++) o[i][j] = row.get(j).getAsFloat(); }
        return o;
    }
    private static float[][][] j3d(JsonArray a, int d0, int d1, int d2) {
        float[][][] o = new float[d0][d1][d2];
        for (int i = 0; i < d0; i++) { JsonArray a1 = a.get(i).getAsJsonArray();
            for (int j = 0; j < d1; j++) { JsonArray a2 = a1.get(j).getAsJsonArray();
                for (int k = 0; k < d2; k++) o[i][j][k] = a2.get(k).getAsFloat(); } }
        return o;
    }
}

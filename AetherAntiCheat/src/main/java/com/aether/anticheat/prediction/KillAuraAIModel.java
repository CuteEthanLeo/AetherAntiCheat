package com.aether.anticheat.prediction;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Statistical classification model for KillAuraAI.
 *
 * <p>Loads CSV training data recorded via /kaai record and builds per-feature
 * statistical profiles (mean, stddev) for both "cheat" and "normal" classes.
 *
 * <p>Detection works by computing weighted z-scores: for each live feature,
 * we measure how many standard deviations it lies from the normal mean,
 * weighted by how discriminative that feature is (cheat vs normal separation).
 *
 * <h3>Feature vector (13 fields from CSV)</h3>
 * <pre>
 *  0: label, 1: timestamp, 2: deltaYaw, 3: deltaPitch, 4: aimError,
 *  5: gcdResY, 6: gcdResP, 7: angVel, 8: angAccel, 9: jerk,
 * 10: atkIntervalMs, 11: cps, 12: flaggedByKA
 * </pre>
 */
public class KillAuraAIModel {

    // Feature indices (skip label=0, timestamp=1)
    public static final int F_DELTA_YAW = 2;
    public static final int F_DELTA_PITCH = 3;
    public static final int F_AIM_ERROR = 4;
    public static final int F_GCD_RES_Y = 5;
    public static final int F_GCD_RES_P = 6;
    public static final int F_ANG_VEL = 7;
    public static final int F_ANG_ACCEL = 8;
    public static final int F_JERK = 9;
    public static final int F_ATK_INTERVAL = 10;
    public static final int F_CPS = 11;
    // ── New features (12-21) ───────────────────────────────────────────
    public static final int F_ATK_YAW = 12;
    public static final int F_ATK_PITCH = 13;
    public static final int F_TGT_YAW = 14;
    public static final int F_TGT_PITCH = 15;
    public static final int F_YAW_ERR = 16;
    public static final int F_PITCH_ERR = 17;
    public static final int F_DISTANCE = 18;
    public static final int F_MOVE_ANGLE = 19;
    public static final int F_SPRINTING = 20;
    public static final int F_BLOCKING = 21;

    public static final int FEATURE_COUNT = 20;

    public static final String[] FEATURE_NAMES = {
            "deltaYaw", "deltaPitch", "aimError", "gcdResY", "gcdResP",
            "angVel", "angAccel", "jerk", "atkIntervalMs", "cps",
            "attackerYaw", "attackerPitch", "targetYaw", "targetPitch",
            "yawError", "pitchError", "distanceToTarget", "movementAngle",
            "sprinting", "blocking"
    };

    /** Per-feature statistics for one class. */
    public static class FeatureStats {
        public final String name;
        public double mean;
        public double stdDev;
        public double min;
        public double max;
        public int count;

        FeatureStats(String name) { this.name = name; }

        public double zScore(double value) {
            if (stdDev < 1e-9) return 0.0;
            return Math.abs(value - mean) / stdDev;
        }

        @Override
        public String toString() {
            return String.format("%s: μ=%.4f σ=%.4f [%.4f–%.4f] n=%d",
                    name, mean, stdDev, min, max, count);
        }
    }

    // ── Model state ──────────────────────────────────────────────────────

    private final Map<String, FeatureStats[]> classStats = new HashMap<>(); // "cheat"|"normal" -> FeatureStats[FEATURE_COUNT]
    private double[] featureWeights; // discriminative weight per feature
    private boolean trained = false;
    private int cheatSamples = 0;
    private int normalSamples = 0;
    private final Logger logger;

    // ── CNN engine ───────────────────────────────────────────────────
    private CNNInferenceEngine cnnEngine;
    private boolean useCNN = false;
    private float cnnThreshold = 0.55f;
    private int minSeqForInference = 12;  // ≥75% of CNN_SEQUENCE_LENGTH (16); avoids zero-padding bias

    public KillAuraAIModel(Logger logger) {
        this.logger = logger;
        this.cnnEngine = new CNNInferenceEngine(logger);
    }

    // ── Training ─────────────────────────────────────────────────────────

    /**
     * Load all CSV files from the training directory and build the model.
     * @param trainingDir the directory containing CSV files
     * @return number of CSV files loaded
     */
    public int loadAndTrain(File trainingDir) {
        classStats.clear();
        cheatSamples = 0;
        normalSamples = 0;

        Map<String, List<double[]>> rawData = new HashMap<>();
        rawData.put("cheat", new ArrayList<>());
        rawData.put("normal", new ArrayList<>());

        if (!trainingDir.exists() || !trainingDir.isDirectory()) {
            logger.info("[KillAuraAI] Training directory not found: " + trainingDir.getPath());
            trained = false;
            return 0;
        }

        File[] csvFiles = trainingDir.listFiles((dir, name) -> name.endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            logger.info("[KillAuraAI] No CSV training files found in " + trainingDir.getPath());
            trained = false;
            return 0;
        }

        int filesLoaded = 0;
        for (File csvFile : csvFiles) {
            int rows = parseCSV(csvFile, rawData);
            if (rows > 0) filesLoaded++;
        }

        cheatSamples = rawData.get("cheat").size();
        normalSamples = rawData.get("normal").size();

        logger.info("[KillAuraAI] Loaded " + filesLoaded + " CSV files: "
                + cheatSamples + " cheat samples, " + normalSamples + " normal samples");

        if (cheatSamples < 10 || normalSamples < 10) {
            logger.warning("[KillAuraAI] Insufficient training data (need ≥10 samples per class). "
                    + "Model NOT trained. Record more data with /aac kaai record.");
            trained = false;
            return filesLoaded;
        }

        // Compute per-class statistics for each feature
        for (String label : new String[]{"cheat", "normal"}) {
            List<double[]> samples = rawData.get(label);
            FeatureStats[] stats = new FeatureStats[FEATURE_COUNT];
            for (int f = 0; f < FEATURE_COUNT; f++) {
                stats[f] = computeStats(FEATURE_NAMES[f], samples, f);
            }
            classStats.put(label, stats);
        }

        // Compute discriminative weights
        computeFeatureWeights();

        trained = true;
        logger.info("[KillAuraAI] Model trained successfully. "
                + "Cheat=" + cheatSamples + " Normal=" + normalSamples);
        return filesLoaded;
    }

    /**
     * Compute per-feature discriminative weights based on class separation.
     * Features where cheat and normal distributions differ most get higher weight.
     */
    private void computeFeatureWeights() {
        featureWeights = new double[FEATURE_COUNT];
        FeatureStats[] cheat = classStats.get("cheat");
        FeatureStats[] normal = classStats.get("normal");

        for (int f = 0; f < FEATURE_COUNT; f++) {
            double cMean = cheat[f].mean;
            double nMean = normal[f].mean;
            double cStd = cheat[f].stdDev;
            double nStd = normal[f].stdDev;
            double pooledStd = Math.sqrt((cStd * cStd + nStd * nStd) / 2.0);

            if (pooledStd < 1e-9) {
                featureWeights[f] = 0.1; // no separation
            } else {
                // Cohen's d-like separation
                double separation = Math.abs(cMean - nMean) / pooledStd;
                featureWeights[f] = Math.min(1.0, Math.max(0.1, separation / 3.0));
            }
        }

        // Log the weights for transparency
        StringBuilder sb = new StringBuilder("[KillAuraAI] Feature weights: ");
        for (int f = 0; f < FEATURE_COUNT; f++) {
            sb.append(String.format("%s=%.2f ", FEATURE_NAMES[f], featureWeights[f]));
        }
        logger.info(sb.toString().trim());
    }

    /**
     * Compute mean, stddev, min, max for one feature across all samples.
     */
    private FeatureStats computeStats(String name, List<double[]> samples, int featureIdx) {
        FeatureStats fs = new FeatureStats(name);
        fs.count = samples.size();
        if (fs.count == 0) return fs;

        // Mean & min/max
        double sum = 0;
        fs.min = Double.MAX_VALUE;
        fs.max = -Double.MAX_VALUE;
        for (double[] row : samples) {
            double v = row[featureIdx];
            sum += v;
            if (v < fs.min) fs.min = v;
            if (v > fs.max) fs.max = v;
        }
        fs.mean = sum / fs.count;

        // Std dev
        double sqSum = 0;
        for (double[] row : samples) {
            double diff = row[featureIdx] - fs.mean;
            sqSum += diff * diff;
        }
        fs.stdDev = Math.sqrt(sqSum / fs.count);

        return fs;
    }

    /**
     * Parse one CSV file and accumulate feature vectors into rawData.
     * @return number of data rows parsed (excluding header)
     */
    private int parseCSV(File file, Map<String, List<double[]>> rawData) {
        int rows = 0;
        boolean warnedOldFormat = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                boolean isNewFormat = parts.length >= 23;
                boolean isOldFormat = parts.length >= 13 && parts.length < 23;
                if (!isNewFormat && !isOldFormat) continue;

                String label = parts[0].trim().toLowerCase();
                if (!label.equals("cheat") && !label.equals("normal")) continue;

                double[] features = new double[FEATURE_COUNT];
                boolean valid = true;

                if (isNewFormat) {
                    // New format: 20 features at indices 2-21
                    for (int f = 0; f < FEATURE_COUNT; f++) {
                        try {
                            features[f] = Double.parseDouble(parts[F_DELTA_YAW + f]);
                        } catch (NumberFormatException e) {
                            valid = false;
                            break;
                        }
                    }
                } else {
                    // Old format: 10 features at indices 2-11, zero-pad the rest
                    if (!warnedOldFormat) {
                        logger.info("[KillAuraAI] Loading old-format CSV (10 features). "
                                + "New features will be zero-padded. File: " + file.getName());
                        warnedOldFormat = true;
                    }
                    for (int f = 0; f < 10; f++) {
                        try {
                            features[f] = Double.parseDouble(parts[F_DELTA_YAW + f]);
                        } catch (NumberFormatException e) {
                            valid = false;
                            break;
                        }
                    }
                    // features[10..19] remain 0.0
                }

                if (valid) {
                    rawData.get(label).add(features);
                    rows++;
                }
            }
        } catch (IOException e) {
            logger.warning("[KillAuraAI] Failed to read CSV: " + file.getName() + " — " + e.getMessage());
        }
        return rows;
    }

    // ── Classification (Anomaly Detection) ───────────────────────────────

    /**
     * Score a live feature vector via anomaly detection against the NORMAL model.
     *
     * Instead of comparing cheat vs normal (fails when distributions overlap),
     * we measure how far the sample is from the NORMAL distribution.
     * Larger distance = more anomalous = more cheat-like.
     *
     * @param features live feature array [FEATURE_COUNT]
     * @return suspicion score 0.0–1.0 (higher = more cheat-like).
     *         Returns 0.0 if model not trained.
     */
    public double scoreSample(double[] features) {
        if (!trained) return 0.0;

        FeatureStats[] normalProfile = classStats.get("normal");

        double anomalySum = 0.0;
        double totalWeight = 0.0;
        int contributingFeatures = 0;

        for (int f = 0; f < FEATURE_COUNT && f < features.length; f++) {
            FeatureStats ns = normalProfile[f];
            if (ns.stdDev < 1e-9) continue; // feature with no variance — skip

            double w = featureWeights[f];
            // How many std devs is this sample from the normal mean?
            double z = ns.zScore(features[f]);

            // Anomaly: z > 2 means outlier (top 5%), z > 3 means extreme outlier
            // Scale: z=2 → 0.5, z=3 → 0.75, z=4 → 1.0
            double anomaly = Math.min(1.0, Math.max(0.0, (z - 1.0) / 3.0));

            anomalySum += w * anomaly;
            totalWeight += w;
            if (anomaly > 0.2) contributingFeatures++;
        }

        if (totalWeight < 0.01) return 0.0;

        double score = anomalySum / totalWeight;

        // Boost if multiple features are anomalous simultaneously
        if (contributingFeatures >= 3) {
            score = Math.min(1.0, score * 1.4);
        } else if (contributingFeatures >= 2) {
            score = Math.min(1.0, score * 1.2);
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Score using individual named features.
     * @deprecated Use {@link #scoreSample(double[])} with the full 20-feature vector instead.
     *             This method only scores the first 10 features (old format) and pads
     *             the remaining 10 with zeros.
     */
    @Deprecated
    public double scoreLiveData(double deltaYaw, double deltaPitch, double aimError,
                                 double gcdResY, double gcdResP, double angVel,
                                 double angAccel, double jerk, double atkIntervalMs, double cps) {
        return scoreSample(new double[]{
                deltaYaw, deltaPitch, aimError, gcdResY, gcdResP,
                angVel, angAccel, jerk, atkIntervalMs, cps,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        });
    }

    // ── CNN Scoring ─────────────────────────────────────────────────

    /**
     * Score a live feature sequence using the CNN model.
     * @param featureSequence [T][20] float matrix, T=CNN_SEQUENCE_LENGTH (16)
     * @return suspicion score 0.0–1.0. Returns 0.0 if CNN not loaded or insufficient data.
     */
    public double scoreCNN(float[][] featureSequence, int actualSamples) {
        if (!useCNN || !cnnEngine.isLoaded()) return 0.0;
        if (actualSamples < minSeqForInference) return 0.0;
        return cnnEngine.predict(featureSequence);
    }

    /**
     * Load the CNN model from exported JSON files.
     * @return true if loaded successfully
     */
    public boolean loadCNNModel(java.io.File weightsFile, java.io.File statsFile) {
        boolean ok = cnnEngine.loadModel(weightsFile, statsFile);
        if (ok) {
            useCNN = true;
            logger.info("[KillAuraAI] CNN model loaded. Switching to CNN inference.");
        }
        return ok;
    }

    public void setUseCNN(boolean v) { this.useCNN = v; }
    public boolean isUsingCNN() { return useCNN && cnnEngine.isLoaded(); }
    public CNNInferenceEngine getCnnEngine() { return cnnEngine; }
    public void setCnnThreshold(float t) { this.cnnThreshold = t; }
    public float getCnnThreshold() { return cnnThreshold; }
    public void setMinSeqForInference(int n) { this.minSeqForInference = n; }

    // ── Query ────────────────────────────────────────────────────────────

    public boolean isTrained() { return trained; }
    public int getCheatSamples() { return cheatSamples; }
    public int getNormalSamples() { return normalSamples; }
    public boolean isCNNLoaded() { return cnnEngine.isLoaded(); }

    public FeatureStats[] getStats(String label) {
        return classStats.get(label.toLowerCase());
    }

    /**
     * Return a human-readable model summary.
     */
    public String getSummary() {
        if (!trained) {
            return "Model NOT trained. Need ≥10 cheat AND ≥10 normal samples.\n"
                    + "Use /aac kaai record cheat/normal to collect data, then /aac kaai train.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("KillAuraAI Model: TRAINED (cheat=").append(cheatSamples)
                .append(" normal=").append(normalSamples).append(")\n");

        FeatureStats[] cheat = classStats.get("cheat");
        FeatureStats[] normal = classStats.get("normal");

        sb.append("┌─ Cheat Profile ──────────────────────────────\n");
        for (int f = 0; f < FEATURE_COUNT; f++) {
            sb.append("│ ").append(cheat[f].toString()).append("\n");
        }
        sb.append("├─ Normal Profile ─────────────────────────────\n");
        for (int f = 0; f < FEATURE_COUNT; f++) {
            sb.append("│ ").append(normal[f].toString()).append("\n");
        }
        sb.append("├─ Weights ────────────────────────────────────\n│ ");
        for (int f = 0; f < FEATURE_COUNT; f++) {
            sb.append(String.format("%s=%.2f ", FEATURE_NAMES[f], featureWeights[f]));
        }
        sb.append("\n└──────────────────────────────────────────────");
        return sb.toString();
    }
}

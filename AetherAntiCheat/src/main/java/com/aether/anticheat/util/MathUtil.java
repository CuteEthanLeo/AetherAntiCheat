package com.aether.anticheat.util;

/**
 * Math utilities for cheat detection.
 */
public final class MathUtil {

    private MathUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the minimum absolute distance between two angles in degrees.
     * Result is in [0, 180], handling 360° wrap-around.
     *
     * @param a first angle in degrees
     * @param b second angle in degrees
     * @return minimum angular distance in [0, 180]
     */
    public static double distBetweenAngles360(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }
}

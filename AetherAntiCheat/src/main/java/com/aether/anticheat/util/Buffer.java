package com.aether.anticheat.util;

/**
 * Simple rolling average buffer.
 * Used by SpeedB for streak detection — values accumulate on positive hits
 * and decay over time.
 */
public class Buffer {

    private final double[] values;
    private int index;
    private int size;

    public Buffer(int capacity) {
        this.values = new double[capacity];
    }

    /**
     * Add a default hit value (1.0) and return the current buffer average.
     */
    public double add() {
        return add(1.0);
    }

    /**
     * Add a custom hit value and return the current buffer average.
     */
    public double add(double value) {
        values[index] = value;
        index = (index + 1) % values.length;
        if (size < values.length) size++;
        return getAverage();
    }

    /**
     * Reduce all stored values by the given amount (clamped to 0).
     */
    public void reduce(double amount) {
        for (int i = 0; i < size; i++) {
            values[i] = Math.max(0.0, values[i] - amount);
        }
    }

    /**
     * Get the current average of all stored values.
     */
    public double getAverage() {
        if (size == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += values[i];
        }
        return sum / size;
    }

    /**
     * Reset the buffer to empty state.
     */
    public void reset() {
        for (int i = 0; i < values.length; i++) {
            values[i] = 0.0;
        }
        index = 0;
        size = 0;
    }
}

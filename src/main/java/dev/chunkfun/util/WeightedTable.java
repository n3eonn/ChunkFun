package dev.chunkfun.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Immutable weighted random table. Weights do not have to sum to any particular value;
 * the configuration layer validates that the configured percentages total 100.
 */
public final class WeightedTable<T> {

    private final List<T> values;
    private final double[] cumulative;
    private final double total;
    private final Map<T, Double> weights;

    private WeightedTable(Map<T, Double> weights) {
        this.weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        this.values = new ArrayList<>(weights.size());
        this.cumulative = new double[weights.size()];
        double sum = 0.0;
        int i = 0;
        for (Map.Entry<T, Double> entry : weights.entrySet()) {
            sum += entry.getValue();
            this.values.add(entry.getKey());
            this.cumulative[i++] = sum;
        }
        this.total = sum;
    }

    /**
     * Creates a table from the given weights. Non-positive weights are dropped.
     *
     * @throws IllegalArgumentException if no positive weight remains
     */
    public static <T> WeightedTable<T> of(Map<T, Double> weights) {
        Map<T, Double> filtered = new LinkedHashMap<>();
        weights.forEach((key, weight) -> {
            if (weight != null && weight > 0.0) {
                filtered.put(key, weight);
            }
        });
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("A weighted table needs at least one positive weight");
        }
        return new WeightedTable<>(filtered);
    }

    public T pick(Random random) {
        double roll = random.nextDouble() * this.total;
        for (int i = 0; i < this.cumulative.length; i++) {
            if (roll < this.cumulative[i]) {
                return this.values.get(i);
            }
        }
        return this.values.getLast();
    }

    public double total() {
        return this.total;
    }

    public Map<T, Double> weights() {
        return this.weights;
    }

    /** Probability of the given value in percent (0-100). */
    public double percentOf(T value) {
        Double weight = this.weights.get(value);
        return weight == null ? 0.0 : weight / this.total * 100.0;
    }
}

package dev.hugo.watcher.ai;

/** Conservative provider cost estimate in USD micros (one millionth of a dollar). */
public record EstimatedCost(long micros) {
    public EstimatedCost {
        if (micros <= 0) throw new IllegalArgumentException("estimated cost must be positive");
    }
}

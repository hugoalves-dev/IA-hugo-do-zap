package dev.hugo.watcher.ai;

/** Provider-reported or reconciled cost in USD micros. */
public record ActualCost(long micros) {
    public ActualCost {
        if (micros < 0) throw new IllegalArgumentException("actual cost cannot be negative");
    }
}

package dev.hugo.watcher.ai;

/** No configured or remaining provider budget. Safe to expose without request data. */
public final class BudgetExceededException extends RuntimeException {
    public BudgetExceededException() { super("AI budget is unavailable or exhausted"); }
}

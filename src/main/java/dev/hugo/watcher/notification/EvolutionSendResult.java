package dev.hugo.watcher.notification;

public record EvolutionSendResult(Outcome outcome) {
    public enum Outcome { DELIVERED, FAILED, UNKNOWN }
    public static EvolutionSendResult delivered() { return new EvolutionSendResult(Outcome.DELIVERED); }
    public static EvolutionSendResult failed() { return new EvolutionSendResult(Outcome.FAILED); }
    public static EvolutionSendResult unknown() { return new EvolutionSendResult(Outcome.UNKNOWN); }
}

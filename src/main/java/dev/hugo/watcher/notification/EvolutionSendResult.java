package dev.hugo.watcher.notification;

public record EvolutionSendResult(Outcome outcome, String providerMessageId) {
    public enum Outcome { DELIVERED, FAILED, UNCERTAIN }
    public static EvolutionSendResult delivered(String id) { return new EvolutionSendResult(Outcome.DELIVERED, id); }
    public static EvolutionSendResult failed() { return new EvolutionSendResult(Outcome.FAILED, null); }
    public static EvolutionSendResult uncertain() { return new EvolutionSendResult(Outcome.UNCERTAIN, null); }
    public static EvolutionSendResult uncertain(String id) { return new EvolutionSendResult(Outcome.UNCERTAIN, id); }
}

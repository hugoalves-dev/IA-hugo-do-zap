package dev.hugo.watcher.telegram;

public record AdminResult(boolean accepted, boolean confirmationRequired, String message) {
    public static AdminResult accepted(String message) { return new AdminResult(true, false, message); }
    public static AdminResult confirmation(String message) { return new AdminResult(true, true, message); }
    public static AdminResult rejected(String message) { return new AdminResult(false, false, message); }
}

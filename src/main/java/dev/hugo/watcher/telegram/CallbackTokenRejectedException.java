package dev.hugo.watcher.telegram;

public final class CallbackTokenRejectedException extends RuntimeException {
    public CallbackTokenRejectedException() { super("callback token is invalid, expired, or already used"); }
}

package dev.hugo.watcher.ai;

public final class ClassificationProviderException extends RuntimeException {
    public enum Kind { TECHNICAL_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, AUTHORIZATION_DENIED,
        PRIVACY_ROUTE_UNAVAILABLE, CONFIGURATION_ERROR }

    private final Kind kind;

    public ClassificationProviderException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public ClassificationProviderException(Kind kind, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
    public boolean isTechnicalFailure() { return kind == Kind.TECHNICAL_FAILURE; }

    public static ClassificationProviderException technical(String provider) {
        return new ClassificationProviderException(Kind.TECHNICAL_FAILURE);
    }

    public static ClassificationProviderException authorizationDenied(String provider) {
        return new ClassificationProviderException(Kind.AUTHORIZATION_DENIED);
    }
}

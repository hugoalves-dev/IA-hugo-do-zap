package dev.hugo.watcher;

import java.time.Instant;

/** Carries the upstream receipt time through DB commit callbacks to notification publication. */
public final class RabbitDeliveryContext {
    private static final ThreadLocal<Instant> INGRESS_AT = new ThreadLocal<>();

    private RabbitDeliveryContext() { }

    public static void set(Instant instant) { INGRESS_AT.set(instant); }
    public static Instant current() { return INGRESS_AT.get(); }
    public static Instant ingressAt() {
        Instant value = INGRESS_AT.get();
        return value == null ? Instant.now() : value;
    }
    public static void clear() { INGRESS_AT.remove(); }
}

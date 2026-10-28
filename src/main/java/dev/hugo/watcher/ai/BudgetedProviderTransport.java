package dev.hugo.watcher.ai;

import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One durable reservation per HTTP attempt, including schema retries and fallback. */
public final class BudgetedProviderTransport implements ProviderTransport {
    private final ProviderTransport delegate;
    private final BudgetService budgets;
    private final BooleanSupplier authorized;
    private final String provider;
    private final long holdMicros;
    private final Clock clock;

    public BudgetedProviderTransport(ProviderTransport delegate, BudgetService budgets, BooleanSupplier authorized,
                                    String provider, long holdMicros, Clock clock) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.budgets = java.util.Objects.requireNonNull(budgets);
        this.authorized = java.util.Objects.requireNonNull(authorized);
        this.provider = provider;
        if (holdMicros <= 0) throw new IllegalArgumentException("positive conservative cost hold required");
        this.holdMicros = holdMicros;
        this.clock = clock;
    }

    @Override public Response send(HttpRequest request, Duration timeout, int maxResponseBytes) {
        if (!authorized.getAsBoolean()) throw ClassificationProviderException.authorizationDenied(provider);
        Reservation reservation = budgets.reserve(new EstimatedCost(holdMicros), clock.instant());
        // Recheck after reserving: consent can be revoked while waiting for the budget lock.
        if (!authorized.getAsBoolean()) {
            budgets.release(reservation.id());
            throw ClassificationProviderException.authorizationDenied(provider);
        }
        budgets.markDispatched(reservation.id());
        Response response;
        try {
            response = delegate.send(request, timeout, maxResponseBytes);
        } catch (RuntimeException error) {
            budgets.markUncertain(reservation.id());
            throw error;
        }
        Long actual = confirmedMicros(response);
        if (actual == null) budgets.markUncertain(reservation.id());
        else budgets.settle(reservation.id(), new ActualCost(actual));
        return response;
    }

    private Long confirmedMicros(Response response) {
        // Token counts are not a bill. Gemini has no confirmed monetary amount here.
        if (!"openrouter".equals(provider) || response == null || response.statusCode() != 200 || response.body() == null)
            return null;
        try {
            Object root = JsonMapper.builder().build().readValue(response.body(), Object.class);
            if (!(root instanceof Map<?, ?> map) || !(map.get("usage") instanceof Map<?, ?> usage)
                    || !(usage.get("cost") instanceof Number amount)) return null;
            BigDecimal cost = new BigDecimal(amount.toString());
            if (cost.signum() < 0) return null;
            return cost.movePointRight(6).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (RuntimeException ignored) { return null; }
    }
}

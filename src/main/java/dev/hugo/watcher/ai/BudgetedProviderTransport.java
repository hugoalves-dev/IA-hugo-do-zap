package dev.hugo.watcher.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(BudgetedProviderTransport.class);
    private final ProviderTransport delegate;
    private final BudgetService budgets;
    private final BooleanSupplier authorized;
    private final String provider;
    private final long holdMicros;
    private final Clock clock;
    private final PromptGenerationContext promptContext;

    public BudgetedProviderTransport(ProviderTransport delegate, BudgetService budgets, BooleanSupplier authorized,
                                    String provider, long holdMicros, Clock clock) {
        this(delegate, budgets, authorized, provider, holdMicros, clock, null);
    }

    public BudgetedProviderTransport(ProviderTransport delegate, BudgetService budgets, BooleanSupplier authorized,
                                    String provider, long holdMicros, Clock clock,
                                    PromptGenerationContext promptContext) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.budgets = java.util.Objects.requireNonNull(budgets);
        this.authorized = java.util.Objects.requireNonNull(authorized);
        this.provider = provider;
        if (holdMicros <= 0) throw new IllegalArgumentException("positive conservative cost hold required");
        this.holdMicros = holdMicros;
        this.clock = clock;
        this.promptContext = promptContext;
    }

    @Override public Response send(HttpRequest request, Duration timeout, int maxResponseBytes) {
        if (!authorized.getAsBoolean()) throw ClassificationProviderException.authorizationDenied(provider);
        Reservation reservation = budgets.reserve(new EstimatedCost(holdMicros), clock.instant());
        logPromptEvent("BUDGET_RESERVED", reservation.id(), null);
        // Recheck after reserving: consent can be revoked while waiting for the budget lock.
        if (!authorized.getAsBoolean()) {
            budgets.release(reservation.id());
            logPromptEvent("BUDGET_RELEASED", reservation.id(), null);
            throw ClassificationProviderException.authorizationDenied(provider);
        }
        budgets.markDispatched(reservation.id());
        logPromptEvent("BUDGET_DISPATCHED", reservation.id(), null);
        Response response;
        logPromptEvent("TRANSPORT_INVOKED", reservation.id(), null);
        try {
            response = delegate.send(request, timeout, maxResponseBytes);
        } catch (RuntimeException error) {
            budgets.markUncertain(reservation.id());
            logPromptEvent("BUDGET_UNCERTAIN", reservation.id(), null, "TRANSPORT_FAILURE");
            logPromptEvent("TRANSPORT_FAILURE", reservation.id(), null);
            throw error;
        }
        logPromptEvent("HTTP_RESPONSE_RECEIVED", reservation.id(), response == null ? null : response.statusCode());
        Long actual = confirmedMicros(response);
        if (actual == null) {
            budgets.markUncertain(reservation.id());
            logPromptEvent("BUDGET_UNCERTAIN", reservation.id(), null,
                    response == null ? "EMPTY_RESPONSE" : "COST_UNCONFIRMED");
        } else {
            budgets.settle(reservation.id(), new ActualCost(actual));
            logPromptEvent("BUDGET_SETTLED", reservation.id(), null);
        }
        return response;
    }

    private void logPromptEvent(String event, java.util.UUID reservationId, Integer httpStatus) {
        logPromptEvent(event, reservationId, httpStatus, null);
    }

    private void logPromptEvent(String event, java.util.UUID reservationId, Integer httpStatus, String reason) {
        if (promptContext == null) return;
        if (httpStatus == null && "BUDGET_UNCERTAIN".equals(event)) {
            log.info("prompt_generation_provider event={} jobId={} operation={} provider={} reservationId={} reason={}",
                    event, promptContext.jobId(), promptContext.operation(), provider, reservationId, reason);
        } else if (httpStatus == null) {
            log.info("prompt_generation_provider event={} jobId={} operation={} provider={} reservationId={}",
                    event, promptContext.jobId(), promptContext.operation(), provider, reservationId);
        } else {
            log.info("prompt_generation_provider event={} jobId={} operation={} provider={} reservationId={} httpStatus={}",
                    event, promptContext.jobId(), promptContext.operation(), provider, reservationId, httpStatus);
        }
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

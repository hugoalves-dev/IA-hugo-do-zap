package dev.hugo.watcher.ai;

import dev.hugo.watcher.policy.Situation;
import dev.hugo.watcher.simulation.Batch;
import dev.hugo.watcher.simulation.Classification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.hugo.watcher.ai.AiClassificationEvaluationRepository.BatchScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Classification only. This component cannot enqueue Telegram alerts or WhatsApp actions. */
@Service
public final class AiSimulationClassifier {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AiClassificationEvaluationRepository persistence;
    private final ConversationContextRepository context;
    private final dev.hugo.watcher.simulation.PersonalReferenceEvidenceRepository referenceEvidence;
    private final BudgetService budgets;
    private final Clock clock;
    private final AiClassificationProperties properties;
    private final ProviderTransport transport;
    private dev.hugo.watcher.OperationalState operational;
    private ShadowMetrics metrics;
    @Autowired(required = false) public void configureMetrics(ShadowMetrics value) { this.metrics = value; }
    private long contextWindowHours = 24;
    @org.springframework.beans.factory.annotation.Value("${watcher.ai.context-window-hours:24}")
    public void configureContextWindow(long hours) {
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("context window must be 1 to 168 hours");
        contextWindowHours = hours;
    }
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    /** Production collaborators keep SQL and message-history reads in repositories. */
    public AiSimulationClassifier(AiClassificationEvaluationRepository persistence,
                                  ConversationContextRepository context,
                                  dev.hugo.watcher.simulation.PersonalReferenceEvidenceRepository referenceEvidence,
                                  BudgetService budgets, Clock clock,
                                  AiClassificationProperties properties, ProviderTransport transport) {
        this.persistence = persistence;
        this.context = context;
        this.referenceEvidence = referenceEvidence;
        this.budgets = budgets;
        this.clock = clock;
        this.properties = properties;
        this.transport = transport;
    }

    /** Compatibility constructors for isolated provider and acceptance tests. */
    public AiSimulationClassifier(JdbcTemplate jdbc, BudgetService budgets, Clock clock,
                                  AiClassificationProperties properties) {
        this(jdbc, budgets, clock, properties, null);
    }
    public AiSimulationClassifier(JdbcTemplate jdbc, BudgetService budgets, Clock clock,
                                  AiClassificationProperties properties, ProviderTransport transport) {
        this(new AiClassificationEvaluationRepository(jdbc),
                new ConversationContextRepository(jdbc, java.time.Duration.ofHours(24)),
                new dev.hugo.watcher.simulation.PersonalReferenceEvidenceRepository(jdbc),
                budgets, clock, properties, transport);
    }

    public Classification classify(Batch batch) {
        long started = System.nanoTime();
        try {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("provider calls must run outside SQL transactions");
        if (!properties.configured()) return unavailable("configuration_invalid");
        String scopeDiagnostic = eligibilityDiagnostic(batch);
        if (scopeDiagnostic != null) return unavailable(scopeDiagnostic);
        Set<Situation> purposes = permittedPurposes(batch, "openrouter");
        if (purposes.isEmpty()) return unavailable("consent_missing");
        purposes = contextualPurposes(batch, purposes);
        if (purposes.isEmpty()) return unavailable("consent_missing");
        final Set<Situation> approvedPurposes = Set.copyOf(purposes);
        ClassificationInput input = context.input(batch, clock.instant(), java.time.Duration.ofHours(contextWindowHours));
        if (input.messages().isEmpty()) {
            if (metrics != null) metrics.contextDiagnostic("no_eligible_text");
            return unavailable("no_eligible_text");
        }
        if (input.contextEntries().isEmpty() && needsContext(input.target())) {
            if (metrics != null) metrics.contextDiagnostic("context_insufficient");
            return unavailable("context_insufficient");
        }
        UUID token = UUID.randomUUID();
        Instant deadline = clock.instant().plus(properties.timeout().multipliedBy(3)).plusSeconds(30);
        int claimed = persistence.claim(batch.id(), token, deadline);
        if (claimed == 0) {
            var previous = persistence.prior(batch.id()).getFirst();
            if (previous.result() != null) return authorizedStored(batch, previous.result());
            if (clock.instant().isBefore(previous.expires())) throw new IllegalStateException("AI batch evaluation in progress");
            return Classification.unavailable(); // crashed/ambiguous claim is never redispatched automatically
        }
        ProviderTransport http = transport == null ? new JdkProviderTransport() : transport;
        var usedProvider = new java.util.concurrent.atomic.AtomicReference<>("openrouter");
        // Revalidate authorization and reserve a separate budget for each HTTP attempt.
        var primary = new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                properties.openRouterModel(), properties.openRouterApiKey(), guarded(http, batch, "openrouter", approvedPurposes, usedProvider),
                (provider, minimized) -> authorized(batch, provider, approvedPurposes), properties.timeout(),
                properties.openRouterMaxTokens(), properties.openRouterReasoningEnabled());
        ClassificationProvider fallback = null;
        if (properties.fallbackConfigured()) fallback = new GeminiProvider(URI.create("https://generativelanguage.googleapis.com/v1beta/models"),
                properties.geminiModel(), properties.geminiApiKey(), guarded(http, batch, "gemini", approvedPurposes, usedProvider),
                (provider, minimized) -> authorized(batch, provider, approvedPurposes), properties.timeout());
        if (metrics != null) metrics.context(input.contextEntries());
        ClassificationResult result = new ClassificationService(primary, fallback, metrics).classify(input);
        Classification classification = authorized(batch, usedProvider.get(), purposes) ? convert(batch, result, purposes) : Classification.unavailable();
        int saved = persistence.saveIfCurrent(batch.id(), token,
                encode(classification, usedProvider.get(), purposes, input.contextEntries(), contextWindowHours),
                clock.instant());
        Classification finalResult = saved == 1 ? classification : Classification.unavailable();
        if (metrics != null) metrics.classification(finalResult.status());
        return finalResult;
        } finally {
            if (metrics != null)
                metrics.classificationLatency(java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private Classification unavailable(String reason) {
        if (metrics != null) metrics.classification(ClassificationResult.Status.UNAVAILABLE);
        return Classification.unavailable(reason);
    }
    private static boolean needsContext(String text) {
        String normalized = text.strip().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+$", "");
        return Set.of("ok", "pode", "apaga", "faz isso", "te respondo depois").contains(normalized);
    }

    /** Recheck data consent and kill switch in the short decision-persistence transaction. Never performs HTTP. */
    public Classification revalidate(Batch batch) {
        return revalidate(batch, Classification.unavailable());
    }
    public Classification revalidate(Batch batch, Classification attempted) {
        var rows = persistence.storedResults(batch.id());
        return rows.isEmpty() ? (attempted.available() ? Classification.unavailable() : attempted) : authorizedStored(batch, rows.getFirst());
    }
    private Classification authorizedStored(Batch batch, String stored) {
        Map<?, ?> metadata = JSON.readValue(stored, Map.class);
        if (!(metadata.get("notificationRecommended") instanceof Boolean)
                || !(metadata.get("replyRecommended") instanceof Boolean)
                || !(metadata.get("evidence") instanceof List<?>))
            return Classification.unavailable("triage_semantics_missing");
        Set<Situation> purposes = new HashSet<>();
        for (Object purpose : (List<?>)metadata.get("purposes")) purposes.add(Situation.valueOf(purpose.toString()));
        if (!authorized(batch, metadata.get("provider").toString(), purposes)) return Classification.unavailable();
        Classification decoded = decode(stored);
        String reference = referenceHash(batch);
        if ((decoded.explicitPersonalReference() || decoded.groupTriggerMatched()) && reference == null) return Classification.unavailable();
        return new Classification(decoded.situations(), decoded.requestDetected(), decoded.notificationRecommended(),
                decoded.replyRecommended(), decoded.evidence(), reference != null,
                reference != null && batch.chatJid().endsWith("@g.us"), decoded.available(), decoded.urgency(),
                decoded.confidence(), decoded.status(), reference, decoded.diagnostic());
    }
    private ProviderTransport guarded(ProviderTransport http, Batch batch, String provider, Set<Situation> purposes,
                                      java.util.concurrent.atomic.AtomicReference<String> usedProvider) {
        ProviderTransport tracked = (request, timeout, max) -> {
            if ("gemini".equals(provider) && "openrouter".equals(usedProvider.getAndSet(provider)) && metrics != null) metrics.fallback();
            else usedProvider.set(provider);
            long start = System.nanoTime();
            String status = "error";
            try {
                ProviderTransport.Response response = http.send(request, timeout, max);
                status = response == null ? "error" : providerStatus(response.statusCode());
                if (metrics != null && "success".equals(status)) metrics.reportedUsage(provider, response.body());
                return response;
            } catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                        status = "timeout";
                        break;
                    }
                }
                throw failure;
            } finally {
                if (metrics != null) metrics.providerCall(provider, status, java.time.Duration.ofNanos(System.nanoTime() - start));
            }
        };
        if (properties.disableLocalBudget()) return (request, timeout, max) -> {
            if (!authorized(batch, provider, purposes)) throw ClassificationProviderException.authorizationDenied(provider);
            return tracked.send(request, timeout, max);
        };
        return new BudgetedProviderTransport(tracked, budgets, () -> authorized(batch, provider, purposes),
                provider, properties.attemptHoldMicros(), clock);
    }
    static String providerStatus(int status) {
        return status == 408 ? "timeout" : status >= 200 && status < 300 ? "success" : "rejected";
    }
    private boolean authorized(Batch batch, String provider, Set<Situation> purposes) {
        return properties.configured() && eligible(batch)
                && !purposes.isEmpty() && contextualPurposes(batch, permittedPurposes(batch, provider)).containsAll(purposes);
    }
    private Set<Situation> contextualPurposes(Batch batch, Set<Situation> consented) {
        boolean group = batch.chatJid().endsWith("@g.us");
        Situation purpose = group ? Situation.GROUP_TRIGGER : Situation.CONTACT_REQUEST;
        boolean mention = structuredMention(batch);
        if (!consented.contains(purpose)) return Set.of();
        Set<Situation> applicable = EnumSet.of(purpose);
        if (mention && consented.contains(Situation.CRITICAL_URGENCY)) applicable.add(Situation.CRITICAL_URGENCY);
        return applicable;
    }
    private Set<Situation> permittedPurposes(Batch batch, String provider) {
        return properties.requireChatConsent() ? consents(batch.chatJid(), provider) : EnumSet.allOf(Situation.class);
    }
    private Set<Situation> consents(String chat, String provider) {
        return new HashSet<>(persistence.consentedSituations(chat, provider, clock.instant()));
    }
    private boolean eligible(Batch batch) {
        return eligibilityDiagnostic(batch) == null;
    }

    /**
     * Returns a fixed, privacy-safe diagnostic for every pre-provider rejection.
     * This must enforce the same fail-closed conditions as the authorization recheck.
     */
    private String eligibilityDiagnostic(Batch batch) {
        if (operational == null || !operational.classification())
            return operational != null && operational.killSwitch()
                    ? "kill_switch_enabled" : "ai_classification_disabled";
        List<BatchScope> scopes = persistence.scopes(batch.id());
        if (scopes.isEmpty()) return "batch_missing";
        BatchScope scope = scopes.getFirst();
        if (!scope.chatJid().equals(batch.chatJid())) return "batch_chat_mismatch";
        if (!Set.of("CLAIMED", "COMPLETED").contains(scope.status())) return "batch_status_ineligible";
        if (!scope.expiresAt().isAfter(clock.instant())) return "batch_expired";
        if (scope.mode() == null) return "archive_settings_missing";
        if ("ALL".equals(scope.mode())) return null;
        if (!scope.synchronizedSnapshot() || scope.archived() == null) return "archive_state_unknown";
        if (("ARCHIVED_ONLY".equals(scope.mode()) && scope.archived()) ||
                ("UNARCHIVED_ONLY".equals(scope.mode()) && !scope.archived())) return null;
        return "archive_scope_excluded";
    }

    private Classification convert(Batch batch, ClassificationResult result, Set<Situation> purposes) {
        if (result.status() != ClassificationResult.Status.CLASSIFIED)
            return new Classification(Set.of(), false, false, false, List.of(), false, false, false,
                    result.urgency(), result.confidence(), result.status(), null, result.diagnostic());
        Set<Situation> situations = EnumSet.noneOf(Situation.class);
        if (result.intent() == ClassificationResult.Intent.CONTACT_REQUEST) situations.add(Situation.CONTACT_REQUEST);
        if (result.intent() == ClassificationResult.Intent.GROUP_TRIGGER) situations.add(Situation.GROUP_TRIGGER);
        if (result.urgency() == ClassificationResult.Urgency.CRITICAL) situations.add(Situation.CRITICAL_URGENCY);
        if (!purposes.containsAll(situations)) return Classification.unavailable();
        boolean mention = structuredMention(batch);
        return Classification.classified(situations, result.requestDetected(), result.notificationRecommended(),
                result.replyRecommended(), result.evidence(), mention, mention && batch.chatJid().endsWith("@g.us"),
                result.urgency(), result.confidence(), referenceHash(batch));
    }
    private String referenceHash(Batch batch) {
        return dev.hugo.watcher.simulation.PersonalReferenceEvidence.findHash(referenceEvidence, batch.id(), properties.ownJid(), clock.instant());
    }
    private boolean structuredMention(Batch batch) { return referenceHash(batch) != null; }
    private static String encode(Classification result, String provider, Set<Situation> purposes,
                                 List<ClassificationInput.ContextEntry> contextEntries, long contextWindowHours) {
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("provider",provider); metadata.put("purposes",purposes.stream().map(Enum::name).toList());
        metadata.put("situations",result.situations().stream().map(Enum::name).toList());
        metadata.put("requestDetected",result.requestDetected());
        metadata.put("notificationRecommended", result.notificationRecommended());
        metadata.put("replyRecommended", result.replyRecommended());
        metadata.put("evidence", result.evidence().stream().map(Enum::name).toList());
        metadata.put("explicitPersonalReference",result.explicitPersonalReference());
        metadata.put("groupTriggerMatched",result.groupTriggerMatched()); metadata.put("available",result.available());
        metadata.put("urgency",result.urgency().name()); metadata.put("confidence",result.confidence()); metadata.put("status",result.status().name());
        metadata.put("personalReferenceHash",result.personalReferenceHash());
        metadata.put("diagnostic", result.diagnostic());
        metadata.put("contextCount", contextEntries.size());
        metadata.put("contextOwnerCount", contextEntries.stream().filter(e -> "owner".equals(e.role())).count());
        metadata.put("contextContactCount", contextEntries.stream().filter(e -> "contact".equals(e.role())).count());
        metadata.put("contextGroupParticipantCount", contextEntries.stream().filter(e -> "group_participant".equals(e.role())).count());
        metadata.put("contextWindowHours", contextWindowHours);
        return JSON.writeValueAsString(metadata);
    }
    private static Classification decode(String value) {
        Map<?, ?> result = JSON.readValue(value, Map.class);
        Set<Situation> situations = new HashSet<>();
        for (Object situation : (List<?>)result.get("situations")) situations.add(Situation.valueOf(situation.toString()));
        if (!(result.get("notificationRecommended") instanceof Boolean notification)
                || !(result.get("replyRecommended") instanceof Boolean reply)
                || !(result.get("evidence") instanceof List<?> evidence))
            return Classification.unavailable("triage_semantics_missing");
        List<ClassificationResult.Evidence> parsedEvidence = evidence.stream()
                .map(item -> ClassificationResult.Evidence.valueOf(item.toString())).toList();
        return new Classification(situations, Boolean.TRUE.equals(result.get("requestDetected")), notification,
                reply, parsedEvidence, Boolean.TRUE.equals(result.get("explicitPersonalReference")),
                Boolean.TRUE.equals(result.get("groupTriggerMatched")), Boolean.TRUE.equals(result.get("available")),
                ClassificationResult.Urgency.valueOf(Objects.toString(result.get("urgency"),"UNCERTAIN")),
                result.get("confidence") instanceof Number n ? n.doubleValue() : 0,
                ClassificationResult.Status.valueOf(Objects.toString(result.get("status"),"UNCERTAIN")),
                (String)result.get("personalReferenceHash"), (String)result.get("diagnostic"));
    }
}

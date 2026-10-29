package dev.hugo.watcher.ai;

import dev.hugo.watcher.policy.Situation;
import dev.hugo.watcher.simulation.Batch;
import dev.hugo.watcher.simulation.Classification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Classification only. This component cannot enqueue Telegram alerts or WhatsApp actions. */
@Service
public final class AiSimulationClassifier {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final BudgetService budgets;
    private final Clock clock;
    private final AiClassificationProperties properties;
    private final ProviderTransport transport;
    private dev.hugo.watcher.OperationalState operational;
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    @Autowired public AiSimulationClassifier(JdbcTemplate jdbc, BudgetService budgets, Clock clock,
                                             AiClassificationProperties properties) {
        this(jdbc, budgets, clock, properties, null);
    }
    public AiSimulationClassifier(JdbcTemplate jdbc, BudgetService budgets, Clock clock,
                                  AiClassificationProperties properties, ProviderTransport transport) {
        this.jdbc = jdbc; this.budgets = budgets; this.clock = clock;
        this.properties = properties; this.transport = transport;
    }

    public Classification classify(Batch batch) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("provider calls must run outside SQL transactions");
        if (!properties.configured() || !eligible(batch)) return Classification.unavailable();
        Set<Situation> purposes = permittedPurposes(batch, "openrouter");
        purposes = contextualPurposes(batch, purposes);
        if (purposes.isEmpty()) return Classification.unavailable();
        final Set<Situation> approvedPurposes = Set.copyOf(purposes);
        ClassificationInput input = ClassificationInput.fromBatch(batch);
        if (input.messages().isEmpty()) return Classification.unavailable();
        UUID token = UUID.randomUUID();
        Instant deadline = clock.instant().plus(properties.timeout().multipliedBy(3)).plusSeconds(30);
        int claimed = jdbc.update("""
                insert into simulation_ai_evaluations(batch_id,lease_token,expires_at) values (?,?,?)
                on conflict(batch_id) do nothing
                """, batch.id(), token, Timestamp.from(deadline));
        if (claimed == 0) {
            var previous = jdbc.query("select result::text,expires_at from simulation_ai_evaluations where batch_id=?",
                    (rs, row) -> new Prior(rs.getString(1), rs.getTimestamp(2).toInstant()), batch.id()).getFirst();
            if (previous.result() != null) return authorizedStored(batch, previous.result());
            if (clock.instant().isBefore(previous.expires())) throw new IllegalStateException("AI batch evaluation in progress");
            return Classification.unavailable(); // crashed/ambiguous claim is never redispatched automatically
        }
        ProviderTransport http = transport == null ? new JdkProviderTransport() : transport;
        var usedProvider = new java.util.concurrent.atomic.AtomicReference<>("openrouter");
        // Revalidate authorization and reserve a separate budget for each HTTP attempt.
        var primary = new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                properties.openRouterModel(), properties.openRouterApiKey(), guarded(http, batch, "openrouter", approvedPurposes, usedProvider),
                (provider, minimized) -> authorized(batch, provider, approvedPurposes), properties.timeout());
        ClassificationProvider fallback = null;
        if (properties.fallbackConfigured()) fallback = new GeminiProvider(URI.create("https://generativelanguage.googleapis.com/v1beta/models"),
                properties.geminiModel(), properties.geminiApiKey(), guarded(http, batch, "gemini", approvedPurposes, usedProvider),
                (provider, minimized) -> authorized(batch, provider, approvedPurposes), properties.timeout());
        ClassificationResult result = new ClassificationService(primary, fallback).classify(input);
        Classification classification = authorized(batch, usedProvider.get(), purposes) ? convert(batch, result, purposes) : Classification.unavailable();
        int saved = jdbc.update("update simulation_ai_evaluations set result=?::jsonb where batch_id=? and lease_token=? and expires_at>?",
                encode(classification, usedProvider.get(), purposes), batch.id(), token, Timestamp.from(clock.instant()));
        return saved == 1 ? classification : Classification.unavailable();
    }

    /** Recheck data consent and kill switch in the short decision-persistence transaction. Never performs HTTP. */
    public Classification revalidate(Batch batch) {
        var rows = jdbc.query("select result::text from simulation_ai_evaluations where batch_id=? and result is not null",
                (rs, row) -> rs.getString(1), batch.id());
        return rows.isEmpty() ? Classification.unavailable() : authorizedStored(batch, rows.getFirst());
    }
    private Classification authorizedStored(Batch batch, String stored) {
        Map<?, ?> metadata = JSON.readValue(stored, Map.class);
        Set<Situation> purposes = new HashSet<>();
        for (Object purpose : (List<?>)metadata.get("purposes")) purposes.add(Situation.valueOf(purpose.toString()));
        if (!authorized(batch, metadata.get("provider").toString(), purposes)) return Classification.unavailable();
        Classification decoded = decode(stored);
        String reference = referenceHash(batch);
        if ((decoded.explicitPersonalReference() || decoded.groupTriggerMatched()) && reference == null) return Classification.unavailable();
        return new Classification(decoded.situations(),decoded.requestDetected(),reference != null,
                reference != null && batch.chatJid().endsWith("@g.us"),decoded.available(),decoded.urgency(),decoded.confidence(),decoded.status(),reference);
    }
    private ProviderTransport guarded(ProviderTransport http, Batch batch, String provider, Set<Situation> purposes,
                                      java.util.concurrent.atomic.AtomicReference<String> usedProvider) {
        ProviderTransport tracked = (request, timeout, max) -> {
            usedProvider.set(provider);
            return http.send(request, timeout, max);
        };
        if (properties.disableLocalBudget()) return (request, timeout, max) -> {
            if (!authorized(batch, provider, purposes)) throw ClassificationProviderException.authorizationDenied(provider);
            return tracked.send(request, timeout, max);
        };
        return new BudgetedProviderTransport(tracked, budgets, () -> authorized(batch, provider, purposes),
                provider, properties.attemptHoldMicros(), clock);
    }
    private boolean authorized(Batch batch, String provider, Set<Situation> purposes) {
        return properties.configured() && eligible(batch)
                && !purposes.isEmpty() && contextualPurposes(batch, permittedPurposes(batch, provider)).containsAll(purposes);
    }
    private Set<Situation> contextualPurposes(Batch batch, Set<Situation> consented) {
        boolean group = batch.chatJid().endsWith("@g.us");
        Situation purpose = group ? Situation.GROUP_TRIGGER : Situation.CONTACT_REQUEST;
        boolean mention = structuredMention(batch);
        if (!consented.contains(purpose) || group && !mention) return Set.of();
        Set<Situation> applicable = EnumSet.of(purpose);
        if (mention && consented.contains(Situation.CRITICAL_URGENCY)) applicable.add(Situation.CRITICAL_URGENCY);
        return applicable;
    }
    private Set<Situation> permittedPurposes(Batch batch, String provider) {
        return properties.requireChatConsent() ? consents(batch.chatJid(), provider) : EnumSet.allOf(Situation.class);
    }
    private Set<Situation> consents(String chat, String provider) {
        return new HashSet<>(jdbc.query("select situation from ai_data_consents where chat_jid=? and provider=? and privacy_approved=true and expires_at>?",
                (rs, row) -> Situation.valueOf(rs.getString(1)), chat, provider, Timestamp.from(clock.instant())));
    }
    private boolean eligible(Batch batch) {
        if (operational == null || !operational.classification()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from archive_monitor_settings s
                    join simulation_batches b on b.batch_id=? left join chat_snapshot c on c.chat_jid=b.chat_jid
                    where s.singleton_id=1
                    and b.chat_jid=? and b.expires_at>? and b.status in ('CLAIMED','COMPLETED')
                    and (s.mode='ALL' or (c.synchronized_snapshot=true and c.archived is not null
                        and ((s.mode='ARCHIVED_ONLY' and c.archived) or (s.mode='UNARCHIVED_ONLY' and not c.archived)))))
                """, Boolean.class, batch.id(), batch.chatJid(), Timestamp.from(clock.instant())));
    }
    private Classification convert(Batch batch, ClassificationResult result, Set<Situation> purposes) {
        if (result.status() != ClassificationResult.Status.CLASSIFIED)
            return new Classification(Set.of(),false,false,false,false,result.urgency(),result.confidence(),result.status(),null);
        Set<Situation> situations = EnumSet.noneOf(Situation.class);
        if (result.intent() == ClassificationResult.Intent.CONTACT_REQUEST) situations.add(Situation.CONTACT_REQUEST);
        if (result.intent() == ClassificationResult.Intent.GROUP_TRIGGER) situations.add(Situation.GROUP_TRIGGER);
        if (result.urgency() == ClassificationResult.Urgency.CRITICAL) situations.add(Situation.CRITICAL_URGENCY);
        if (!purposes.containsAll(situations)) return Classification.unavailable();
        boolean mention = structuredMention(batch);
        return new Classification(situations, result.requestDetected(), mention, mention && batch.chatJid().endsWith("@g.us"), true,
                result.urgency(), result.confidence(), result.status(), referenceHash(batch));
    }
    private String referenceHash(Batch batch) {
        return dev.hugo.watcher.simulation.PersonalReferenceEvidence.findHash(jdbc,batch.id(),properties.ownJid(),clock.instant());
    }
    private boolean structuredMention(Batch batch) { return referenceHash(batch) != null; }
    private static String encode(Classification result, String provider, Set<Situation> purposes) {
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("provider",provider); metadata.put("purposes",purposes.stream().map(Enum::name).toList());
        metadata.put("situations",result.situations().stream().map(Enum::name).toList());
        metadata.put("requestDetected",result.requestDetected()); metadata.put("explicitPersonalReference",result.explicitPersonalReference());
        metadata.put("groupTriggerMatched",result.groupTriggerMatched()); metadata.put("available",result.available());
        metadata.put("urgency",result.urgency().name()); metadata.put("confidence",result.confidence()); metadata.put("status",result.status().name());
        metadata.put("personalReferenceHash",result.personalReferenceHash());
        return JSON.writeValueAsString(metadata);
    }
    private static Classification decode(String value) {
        Map<?, ?> result = JSON.readValue(value, Map.class);
        Set<Situation> situations = new HashSet<>();
        for (Object situation : (List<?>)result.get("situations")) situations.add(Situation.valueOf(situation.toString()));
        return new Classification(situations, Boolean.TRUE.equals(result.get("requestDetected")),
                Boolean.TRUE.equals(result.get("explicitPersonalReference")), Boolean.TRUE.equals(result.get("groupTriggerMatched")), Boolean.TRUE.equals(result.get("available")),
                ClassificationResult.Urgency.valueOf(Objects.toString(result.get("urgency"),"UNCERTAIN")),
                result.get("confidence") instanceof Number n ? n.doubleValue() : 0,
                ClassificationResult.Status.valueOf(Objects.toString(result.get("status"),"UNCERTAIN")),
                (String)result.get("personalReferenceHash"));
    }
    private record Prior(String result, Instant expires) { }
}

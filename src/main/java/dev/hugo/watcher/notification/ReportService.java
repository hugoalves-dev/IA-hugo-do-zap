package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Creates aggregate-only daily reports while persistence stays in repositories. */
public class ReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ReportQueryRepository queries;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    public ReportService(ReportQueryRepository queries, PlatformTransactionManager manager,
                         NotificationOutboxRepository outbox, Clock clock) {
        this.queries = queries;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Compatibility constructor for standalone integration tests. */
    public ReportService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                         NotificationOutboxRepository outbox, Clock clock) {
        this(new ReportQueryRepository(jdbc), manager, outbox, clock);
    }

    /** Automatic daily delivery stays idempotent for each reporting date. */
    public OutboxAction schedule(LocalDate date) {
        return enqueue(date, "simulation_daily_summary:" + date);
    }

    /** Private administrator commands need a fresh snapshot on every request. */
    public OutboxAction scheduleOnDemand(LocalDate date) {
        return enqueue(date, "simulation_daily_summary:manual:" + date + ":" + java.util.UUID.randomUUID());
    }

    private OutboxAction enqueue(LocalDate date, String key) {
        if (date == null) throw new IllegalArgumentException("report date is required");
        return transactions.execute(status -> {
            queries.lockDailyReport();
            var existing = queries.findOutbox(key);
            if (!existing.isEmpty()) return existing.getFirst();

            Instant start = date.atStartOfDay(REPORT_ZONE).toInstant();
            Instant end = date.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
            var counts = queries.decisionCounts(start, end);
            var triage = queries.triageCounts(start, end);
            var distribution = queries.urgencyCounts(start, end);
            var costs = queries.budgetCosts(start, end);
            Instant created = clock.instant();
            String payload = JSON.writeValueAsString(Map.ofEntries(
                    Map.entry("eventType", "simulation_daily_summary"), Map.entry("date", date.toString()),
                    Map.entry("decisionCount", counts.decisions()), Map.entry("predictedAlerts", counts.alerts()),
                    Map.entry("predictedReplies", counts.replies()), Map.entry("blockedDecisions", counts.blocked()),
                    Map.entry("semanticTriage", Map.of("IGNORE", triage.ignore(), "NOTIFY_ONLY", triage.notifyOnly(),
                            "NOTIFY_AND_REPLY", triage.notifyAndReply(), "UNDECIDED", triage.undecided())),
                    Map.entry("recommendations", Map.of("notifications", triage.notificationRecommendations(),
                            "replies", triage.replyRecommendations())),
                    Map.entry("simulatedActions", Map.of("alerts", counts.alerts(), "replies", counts.replies())),
                    Map.entry("realAuthorizedActions", counts.realAuthorized()),
                    Map.entry("reservedCostMicros", costs.reserved()), Map.entry("reportedActualCostMicros", costs.actual()),
                    Map.entry("urgencyDistribution", Map.of("LOW", distribution.get("low"), "NORMAL", distribution.get("normal"),
                            "HIGH", distribution.get("high"), "CRITICAL", distribution.get("critical"), "UNCERTAIN", distribution.get("uncertain"))),
                    Map.entry("uncertainCount", distribution.get("uncertain_count")),
                    Map.entry("unavailableCount", distribution.get("unavailable_count"))));
            outbox.enqueue(key, "simulation_daily_summary", payload, "[]", created, created.plusSeconds(86_400));
            return queries.requireOutbox(key);
        });
    }
}

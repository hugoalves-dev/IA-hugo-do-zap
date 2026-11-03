package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import dev.hugo.watcher.ai.BudgetLedgerRepository;
import dev.hugo.watcher.ai.BudgetService;
import io.micrometer.core.instrument.MeterRegistry;
import dev.hugo.watcher.policy.AvailabilityOverrideDataRepository;
import dev.hugo.watcher.policy.ChatPolicyOverrideDataRepository;
import dev.hugo.watcher.policy.GlobalPolicyDataRepository;
import dev.hugo.watcher.policy.GlobalPolicyRepository;
import dev.hugo.watcher.policy.HolidayCalendarQueryRepository;
import dev.hugo.watcher.policy.HolidayService;
import dev.hugo.watcher.policy.PersistedPolicyRepository;
import dev.hugo.watcher.policy.PolicyProfileDataRepository;
import dev.hugo.watcher.policy.PolicyResolver;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import dev.hugo.watcher.notification.ReportQueryRepository;
import dev.hugo.watcher.notification.ReportService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit wiring for services that retain legacy constructors during migration #24.
 * Avoid selecting a JDBC compatibility constructor by accident.
 * Remove these factories once each class has only one production constructor.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceBeansConfiguration {

    @Bean
    GlobalPolicyRepository globalPolicyRepository(AvailabilityOverrideDataRepository availability) {
        return new GlobalPolicyRepository(availability);
    }

    @Bean
    PersistedPolicyRepository persistedPolicyRepository(
            PolicyResolver resolver,
            GlobalPolicyRepository globalPolicies,
            GlobalPolicyDataRepository globals,
            PolicyProfileDataRepository profiles,
            ChatPolicyOverrideDataRepository overrides) {
        return new PersistedPolicyRepository(resolver, globalPolicies, globals, profiles, overrides);
    }

    @Bean
    HolidayService holidayService(HolidayCalendarQueryRepository calendar) {
        return new HolidayService(calendar);
    }

    @Bean
    ArchiveModeService archiveModeService(ArchiveModeDataRepository archiveModeData,
                                          PlatformTransactionManager transactions) {
        return new ArchiveModeService(archiveModeData, transactions);
    }

    @Bean
    ConnectionService connectionService(ConnectionStateRepository states,
                                        PlatformTransactionManager transactions,
                                        NotificationOutboxRepository outbox, WatcherProperties properties) {
        return new ConnectionService(states, transactions, outbox, properties);
    }

    @Bean
    OperationalState operationalState(OperationalRestrictionDataRepository restrictions,
                                      AutomationProperties automation, AiClassificationProperties ai,
                                      WatcherSimulationProperties simulation,
                                      @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realMode) {
        return new OperationalState(restrictions, automation, ai, simulation, realMode);
    }

    @Bean
    RetentionService retentionService(RetentionRepository retentionRepository,
                                      PlatformTransactionManager transactions) {
        return new RetentionService(retentionRepository, transactions);
    }

    @Bean
    ReportService reportService(ReportQueryRepository queries,
                                PlatformTransactionManager transactions,
                                NotificationOutboxRepository outbox, Clock clock) {
        return new ReportService(queries, transactions, outbox, clock);
    }
    @Bean
    BudgetService budgetService(BudgetLedgerRepository ledger, PlatformTransactionManager transactions,
                                MeterRegistry metrics) {
        return new BudgetService(ledger, transactions, metrics);
    }

    @Bean
    ChatStateService chatStateService(ChatSnapshotRepository snapshots,
                                      PlatformTransactionManager transactions,
                                      IdentityService identities, ArchiveModeService archiveModes) {
        return new ChatStateService(snapshots, transactions, identities, archiveModes);
    }

    @Bean
    MessageService messageService(MessagePersistenceRepository persistence, PlatformTransactionManager transactions,
                                  IdentityService identities, WatcherProperties properties, ArchiveModeService archiveModes,
                                  dev.hugo.watcher.simulation.DebounceService debounce,
                                  WatcherSimulationProperties simulationProperties,
                                  @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realModeEnabled) {
        return new MessageService(persistence, transactions, identities, properties, archiveModes,
                debounce, simulationProperties, realModeEnabled);
    }

    @Bean
    EvolutionJobProcessor evolutionJobProcessor(EvolutionJobEnvelopeRepository envelopes,
                                                JobRepository jobs, EvolutionEventMapper mapper,
                                                MessageService messages, IdentityService identities,
                                                Clock clock, PlatformTransactionManager transactions) {
        return new EvolutionJobProcessor(envelopes, jobs, mapper, messages, identities, clock, transactions);
    }


    @Bean
    dev.hugo.watcher.telegram.AdminCommandService adminCommandService(
            dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository persistence,
            dev.hugo.watcher.telegram.ShadowDecisionQuery decisions,
            PlatformTransactionManager transactions,
            dev.hugo.watcher.telegram.CallbackTokenService tokens,
            NotificationOutboxRepository outbox, Clock clock, ReportService reports) {
        return new dev.hugo.watcher.telegram.AdminCommandService(
                persistence, decisions, transactions, tokens, outbox, clock, reports);
    }

    @Bean
    dev.hugo.watcher.notification.DecisionRuntimeGate decisionRuntimeGate(
            dev.hugo.watcher.notification.DecisionRuntimeQueryRepository queries,
            Clock clock, WatcherProperties watcher,
            @Value("${watcher.whatsapp-actions.owner-jid:}") String ownerJid,
            PersistedPolicyRepository policies, HolidayService holidays) {
        return new dev.hugo.watcher.notification.DecisionRuntimeGate(
                queries, clock, watcher, ownerJid, policies, holidays);
    }

    @Bean
    dev.hugo.watcher.notification.IntelligentAlertService intelligentAlertService(
            dev.hugo.watcher.notification.IntelligentAlertPersistenceRepository persistence,
            PlatformTransactionManager transactions, NotificationOutboxRepository outbox, Clock clock,
            @Value("${watcher.telegram.intelligent-alerts-enabled:false}") boolean enabled,
            @Value("${watcher.whatsapp-actions.owner-jid:}") String ownerJid) {
        return new dev.hugo.watcher.notification.IntelligentAlertService(
                persistence, transactions, outbox, clock, enabled, ownerJid);
    }

    @Bean
    dev.hugo.watcher.notification.DecisionDeliveryCoordinator decisionDeliveryCoordinator(
            dev.hugo.watcher.notification.DeliveryAdmissionRepository admission, PlatformTransactionManager transactions) {
        return new dev.hugo.watcher.notification.DecisionDeliveryCoordinator(admission, transactions);
    }

    @Bean
    NotificationService notificationService(NotificationQueueQueryRepository queries,
                                            PlatformTransactionManager transactions,
                                            NotificationOutboxRepository outbox,
                                            TelegramTransport telegram, TelegramMessageFormatter telegramFormatter,
                                            WatcherProperties properties, Clock clock, ArchiveModeService archiveModes,
                                            dev.hugo.watcher.notification.IntelligentAlertService intelligentAlerts,
                                            dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries) {
        return new NotificationService(queries, transactions, outbox, telegram, telegramFormatter, properties,
                clock, archiveModes, intelligentAlerts, deliveries);
    }

}

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
    dev.hugo.watcher.ai.ConversationContextRepository conversationContextRepository(
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new dev.hugo.watcher.ai.ConversationContextRepository(jdbc);
    }

    @Bean
    NotificationOutboxRepository notificationOutboxRepository(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries,
            org.springframework.beans.factory.ObjectProvider<RabbitEventPublisher> publisher) {
        return new NotificationOutboxRepository(jdbc, transactions, deliveries, publisher.getIfAvailable());
    }

    @Bean
    dev.hugo.watcher.ai.AiSimulationClassifier aiSimulationClassifier(
            dev.hugo.watcher.ai.AiClassificationEvaluationRepository persistence,
            dev.hugo.watcher.ai.ConversationContextRepository context,
            dev.hugo.watcher.simulation.PersonalReferenceEvidenceRepository references,
            BudgetService budget, Clock clock, AiClassificationProperties properties) {
        return new dev.hugo.watcher.ai.AiSimulationClassifier(
                persistence, context, references, budget, clock, properties, null);
    }

    @Bean
    dev.hugo.watcher.simulation.SimulationService simulationService(
            dev.hugo.watcher.simulation.SimulationPersistenceRepository persistence,
            PlatformTransactionManager transactions, Clock clock,
            dev.hugo.watcher.simulation.WatcherSimulationProperties properties,
            dev.hugo.watcher.simulation.DecisionEngine decisions,
            PersistedPolicyRepository policies, dev.hugo.watcher.policy.AvailabilityService availability,
            WatcherProperties watcher, dev.hugo.watcher.ai.AiSimulationClassifier classifier,
            dev.hugo.watcher.notification.DecisionActionProducer producer) {
        return new dev.hugo.watcher.simulation.SimulationService(
                persistence, transactions, clock, properties, decisions, policies,
                availability, watcher, classifier, producer);
    }

    @Bean
    dev.hugo.watcher.simulation.DebounceService debounceService(
            dev.hugo.watcher.simulation.DebouncePersistenceRepository persistence,
            dev.hugo.watcher.simulation.WatcherSimulationProperties limits,
            PlatformTransactionManager transactions,
            @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realModeEnabled) {
        return new dev.hugo.watcher.simulation.DebounceService(persistence, limits, transactions, realModeEnabled);
    }

    @Bean
    LegacyImportService legacyImportService(LegacySqliteSnapshotRepository reader,
                                            LegacyImportPersistenceRepository persistence,
                                            PlatformTransactionManager transactions) {
        return new LegacyImportService(reader, persistence, transactions);
    }

    @Bean
    IdentityService identityService(IdentityMergeRepository identityStorage,
                                    PlatformTransactionManager transactions) {
        return new IdentityService(identityStorage, transactions);
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
            NotificationOutboxRepository outbox, Clock clock, ReportService reports,
            IdentityService identities) {
        return new dev.hugo.watcher.telegram.AdminCommandService(
                persistence, decisions, transactions, tokens, outbox, clock, reports, identities);
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
    dev.hugo.watcher.notification.WhatsAppActionDispatcher whatsAppActionDispatcher(
            dev.hugo.watcher.notification.WhatsAppActionDispatchRepository persistence,
            PlatformTransactionManager transactions,
            dev.hugo.watcher.notification.WhatsAppActionService actions,
            dev.hugo.watcher.notification.EvolutionActionTransport transport,
            Clock clock,
            @Value("${watcher.whatsapp-actions.real-send-enabled:false}") boolean realSendEnabled,
            dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries) {
        return new dev.hugo.watcher.notification.WhatsAppActionDispatcher(
                persistence, transactions, actions, transport, clock, realSendEnabled, deliveries);
    }

    @Bean
    dev.hugo.watcher.notification.WhatsAppActionService whatsAppActionService(
            dev.hugo.watcher.notification.WhatsAppActionPersistenceRepository persistence,
            PlatformTransactionManager transactions, Clock clock,
            @Value("${watcher.whatsapp-actions.real-send-enabled:false}") boolean realSendEnabled) {
        return new dev.hugo.watcher.notification.WhatsAppActionService(
                persistence, transactions, clock, realSendEnabled);
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
                                            dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries,
                                            @Value("${watcher.ai.triage.legacy-filter-enabled:false}") boolean triageLegacyFilterEnabled) {
        return new NotificationService(queries, transactions, outbox, telegram, telegramFormatter, properties,
                clock, archiveModes, intelligentAlerts, deliveries, triageLegacyFilterEnabled);
    }

}

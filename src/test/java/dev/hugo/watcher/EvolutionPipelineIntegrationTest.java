package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "watcher.runtime.scheduling-enabled=false")
@Import(EvolutionPipelineIntegrationTest.TestTelegramConfiguration.class)
class EvolutionPipelineIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TOKEN = "pipeline-test-token";
    private static final FakeTelegram TELEGRAM = new FakeTelegram();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.telegram-bot-token", () -> "synthetic-test-token");
        registry.add("watcher.telegram-chat-id", () -> "123456789");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> TOKEN);
        registry.add("watcher.evolution.instance", () -> "pipeline-test");
        registry.add("watcher.evolution.max-body-bytes", () -> "4096");
        registry.add("watcher.archive-mode.api-token", () -> "archive-mode-test-token");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EvolutionJobProcessor jobs;
    @Autowired JobRepository jobRepository;
    @Autowired ChatStateService chatStates;
    @Autowired NotificationService notifications;
    @Autowired EvolutionEventMapper mapper;
    @Autowired MessageService messages;
    @Autowired java.time.Clock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired IdentityService identities;
    @Autowired WatcherProperties watcherProperties;
    @Autowired ArchiveModeService archiveModes;
    @LocalServerPort int port;

    @BeforeEach void resetLocalState() {
        archiveModes.setMode(ArchiveMode.ALL);
        jdbc.execute("truncate table outbox_actions, pending_notification, processed_message, quarantined_events, processing_jobs, inbox_events, chat_snapshot, contact_identity, identity_aliases restart identity cascade");
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=now()-interval '1 second' where singleton_id=1");
        TELEGRAM.reset(true);
    }

    @Test void evolutionContactWebhookStoresUnverifiedNameWithoutOverwritingAddressBookName() throws Exception {
        String jid = "5511999999999@s.whatsapp.net";
        String contact = """
                {"event":"contacts.upsert","instance":"pipeline-test","data":[
                {"remoteJid":"5511999999999@s.whatsapp.net","pushName":"Loja & Café","profilePicUrl":null}]}
                """;
        assertEquals(202, post(contact).statusCode());
        assertEquals(1, jobs.processAvailable());
        assertEquals("Loja & Café", identities.resolveDisplayName(jid));
        assertEquals("NOTIFY", jdbc.queryForObject("select display_name_source from contact_identity where canonical_jid=?", String.class, jid));
        identities.observeContactName(jid, "Agenda", ContactNameSource.CONTACT_NAME);
        assertEquals(202, post(contact.replace("Loja & Café", "Outro nome")).statusCode());
        assertEquals(1, jobs.processAvailable());
        assertEquals("Agenda", identities.resolveDisplayName(jid));
    }

    @Test void incomingPushNameSurvivesConfirmedLidPhoneMergeAndRendersEscaped() throws Exception {
        String lid = "105342847439087@lid";
        String phone = "5511999998888@s.whatsapp.net";
        String payload = evolutionLiveEvent("name-merge", lid, phone, "Olá", Instant.now().getEpochSecond())
                .replace("\"status\":\"DELIVERY_ACK\"", "\"pushName\":\"Loja <Café>\",\"status\":\"DELIVERY_ACK\"");
        assertEquals(202, post(payload).statusCode());
        assertEquals(1, jobs.processAvailable());
        assertEquals("Loja <Café>", identities.resolveDisplayName(phone));
        notifications.flush(Instant.now());
        assertTrue(TELEGRAM.lastMessage().contains("Loja &lt;Café&gt;"));
        assertTrue(TELEGRAM.lastMessage().contains("https://wa.me/5511999998888"));
    }

    @Test void contactUpdatesIgnoreMalformedNamesAndDoNotInventAliasLinks() throws Exception {
        String body = """
                {"event":"contacts.update","instance":"pipeline-test","data":[
                  {"remoteJid":"105342847439087@lid","pushName":"Nome novo"},
                  {"remoteJid":"5511999998888@s.whatsapp.net","pushName":"5511999998888"},
                  {"remoteJid":"120363000000000000@g.us","pushName":"Grupo"},
                  {"remoteJid":17,"pushName":"Inválido"}, null]}
                """;
        assertEquals(202, post(body).statusCode());
        assertEquals(1, jobs.processAvailable());
        assertEquals("Nome novo", identities.resolveDisplayName("105342847439087@lid"));
        assertEquals(0, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from contact_identity where canonical_jid like '%@g.us'", Integer.class));
        assertEquals(null, jdbc.queryForObject("select display_name from contact_identity where canonical_jid=?", String.class, "5511999998888@s.whatsapp.net"));
    }

    @Test void duplicateWebhookSurvivesWorkerRestartAndOutboxRetryBeforeFakeTelegramAcknowledges() throws Exception {
        String jid = "5511999999999@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-test"), Optional.of("Contato"), false, java.util.Map.of("synthetic", true)));

        assertEquals(202, post(event("pipeline-event-1", jid, "Olá")).statusCode());
        assertEquals(200, post(event("pipeline-event-1", jid, "Olá")).statusCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));

        var abandoned = jobRepository.claim(1, Instant.now(), Duration.ofSeconds(60)).getFirst();
        jdbc.update("update processing_jobs set lease_expires_at=now()-interval '1 second' where job_uuid=?", abandoned.id());
        jobs.processAvailable();

        assertEquals("COMPLETED", jdbc.queryForObject("select status from processing_jobs", String.class));
        assertEquals(2, jdbc.queryForObject("select attempts from processing_jobs", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));

        TELEGRAM.setAccepted(false);
        notifications.flush(Instant.now());
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions", String.class));
        assertEquals(1, jdbc.queryForObject("select attempts from outbox_actions", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));

        TELEGRAM.setAccepted(true);
        jdbc.update("update outbox_actions set available_at=now()-interval '1 second'");
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=now()-interval '1 second' where singleton_id=1");
        notifications.flush(Instant.now());

        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertTrue(TELEGRAM.lastMessage().contains("Olá"));
    }

    @Test void evolutionV237FreshUpsertWithoutBaileysTypeIsProcessedAndDeduplicated() throws Exception {
        String jid = "105342847439087@lid";
        String alternate = "5511999998888@s.whatsapp.net";
        String payload = evolutionLiveEvent("evolution-v237-live", jid, alternate, "Teste do webhook",
                Instant.now().getEpochSecond());

        assertEquals(202, post(payload).statusCode());
        assertEquals(200, post(payload).statusCode());
        jobs.processAvailable();

        assertEquals(1, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from quarantined_events", Integer.class));
        assertEquals("COMPLETED", jdbc.queryForObject("select status from processing_jobs", String.class));
    }

    @Test void missingBaileysTypeStillRejectsHistoryAppendStaleAndUnverifiedUpserts() throws Exception {
        long current = Instant.now().getEpochSecond();
        String jid = "5511999997777@s.whatsapp.net";
        String base = evolutionLiveEvent("missing-type-test", jid, jid, "Teste", current);
        String old = evolutionLiveEvent("stale-upsert", jid, jid, "Histórico antigo", current - 660);
        String append = base.replace("\"missing-type-test\"", "\"explicit-append\"")
                .replace("\"status\":\"DELIVERY_ACK\"", "\"type\":\"append\",\"status\":\"DELIVERY_ACK\"");
        String history = base.replace("\"missing-type-test\"", "\"explicit-history\"")
                .replace("\"status\":\"DELIVERY_ACK\"", "\"isHistory\":true,\"status\":\"DELIVERY_ACK\"");
        String missingEvidence = base.replace("\"missing-type-test\"", "\"unverified-upsert\"")
                .replace("\"status\":\"DELIVERY_ACK\"", "\"status\":\"UNKNOWN\"");

        assertEquals(202, post(old).statusCode());
        assertEquals(202, post(append).statusCode());
        assertEquals(202, post(history).statusCode());
        assertEquals(202, post(missingEvidence).statusCode());
        jobs.processAvailable();

        assertEquals(4, jdbc.queryForObject("select count(*) from quarantined_events where reason='unverified_message_metadata'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void allModeAcceptsUnknownArchiveStateButFilteredModeQuarantinesIt() throws Exception {
        String jid = "5511888888888@s.whatsapp.net";
        assertEquals(202, post(event("unclassified", jid, "Tipo ausente").replace("\"type\":\"notify\",", "")).statusCode());
        jobs.processAvailable();
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where source_event_id='unclassified' and reason='unverified_message_metadata'", Integer.class));

        assertEquals(202, post(event("unknown-state", jid, "Sem snapshot")).statusCode());
        jobs.processAvailable();
        assertEquals(0, jdbc.queryForObject("select count(*) from quarantined_events where reason='archive_state_unknown_or_unsynchronized'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));

        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        assertEquals(202, post(event("unknown-state-filtered", "5511666666666@s.whatsapp.net", "Filtrado")).statusCode());
        jobs.processAvailable();
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where reason='archive_state_unknown_or_unsynchronized'", Integer.class));

        String retryJid = "5511777777777@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(retryJid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-retry"), Optional.of("Contato"), false, java.util.Map.of("synthetic", true)));
        assertEquals(202, post(event("database-retry", retryJid, "Tentar de novo")).statusCode());
        jdbc.execute("alter table chat_snapshot rename to chat_snapshot_temporarily_unavailable");
        try {
            jobs.processAvailable();
        } finally {
            jdbc.execute("alter table chat_snapshot_temporarily_unavailable rename to chat_snapshot");
        }

        assertEquals("PENDING", jdbc.queryForObject("select status from processing_jobs where inbox_event_id=(select id from inbox_events where external_id='database-retry')", String.class));
        assertEquals(1, jdbc.queryForObject("select attempts from processing_jobs where inbox_event_id=(select id from inbox_events where external_id='database-retry')", Integer.class));
        jdbc.update("update processing_jobs set available_at=now()-interval '1 second' where inbox_event_id=(select id from inbox_events where external_id='database-retry')");
        jobs.processAvailable();
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, retryJid));
    }

    @Test void archiveModeApiRequiresDedicatedBearerAndChangesPersistedMode() throws Exception {
        var unauthorized = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/archive-mode"))
                .GET().build();
        assertEquals(401, HttpClient.newHttpClient().send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());

        var update = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/archive-mode"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer archive-mode-test-token")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"mode\":\"UNARCHIVED_ONLY\"}")).build();
        assertEquals(200, HttpClient.newHttpClient().send(update, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(ArchiveMode.UNARCHIVED_ONLY, archiveModes.getMode());
    }

    @Test void concurrentWorkersClaimOnlyOneJobAtATimeInsteadOfLeasingABatch() throws Exception {
        for (int i = 1; i <= 2; i++) {
            String jid = "551166666666" + i + "@s.whatsapp.net";
            chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                    Optional.of("snapshot-concurrent-" + i), Optional.of("Contato"), false, java.util.Map.of()));
            assertEquals(202, post(event("concurrent-" + i, jid, "Oi" + i)).statusCode());
        }

        var barrier = new CyclicBarrier(2);
        var firstClaimSizes = new ConcurrentHashMap<Thread, Integer>();
        var rendezvousRepository = new JobRepository() {
            @Override public List<Job> claim(int limit, Instant now, Duration leaseDuration) {
                List<Job> claimed = jobRepository.claim(limit, now, leaseDuration);
                if (firstClaimSizes.putIfAbsent(Thread.currentThread(), claimed.size()) == null) {
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (Exception ex) { throw new IllegalStateException("workers did not rendezvous", ex); }
                }
                return claimed;
            }
            @Override public void complete(UUID jobId, UUID leaseToken) { jobRepository.complete(jobId, leaseToken); }
            @Override public boolean expire(UUID jobId, UUID leaseToken) { return jobRepository.expire(jobId, leaseToken); }
            @Override public boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt) {
                return jobRepository.retry(jobId, leaseToken, now, availableAt);
            }
        };
        var workerA = new EvolutionJobProcessor(jdbc, rendezvousRepository, mapper, messages, clock, transactionManager);
        var workerB = new EvolutionJobProcessor(jdbc, rendezvousRepository, mapper, messages, clock, transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(workerA::processAvailable);
            var second = pool.submit(workerB::processAvailable);
            assertEquals(1, first.get(20, TimeUnit.SECONDS));
            assertEquals(1, second.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of(1, 1), firstClaimSizes.values().stream().sorted().toList());
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from processing_jobs where status='COMPLETED'", Integer.class));
    }

    @Test void doesNotProcessPayloadThatExpiresAfterTheJobWasClaimed() throws Exception {
        String jid = "5511555555555@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-expiring"), Optional.of("Contato"), false, java.util.Map.of()));
        assertEquals(202, post(event("expires-after-claim", jid, "Conteúdo expirado")).statusCode());

        JobRepository expireAfterClaim = new JobRepository() {
            @Override public List<Job> claim(int limit, Instant now, Duration leaseDuration) {
                List<Job> claimed = jobRepository.claim(limit, now, leaseDuration);
                if (!claimed.isEmpty()) jdbc.update("update inbox_events set expires_at=now()-interval '1 second' where id=?",
                        claimed.getFirst().inboxEventId());
                return claimed;
            }
            @Override public void complete(UUID jobId, UUID leaseToken) { jobRepository.complete(jobId, leaseToken); }
            @Override public boolean expire(UUID jobId, UUID leaseToken) { return jobRepository.expire(jobId, leaseToken); }
            @Override public boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt) {
                return jobRepository.retry(jobId, leaseToken, now, availableAt);
            }
        };

        var processor = new EvolutionJobProcessor(jdbc, expireAfterClaim, mapper, messages, clock, transactionManager);
        assertEquals(0, processor.processAvailable());
        assertEquals("EXPIRED", jdbc.queryForObject("select status from processing_jobs", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void retentionDefersScrubOfAnInFlightJobUntilEffectsCommit() throws Exception {
        String jid = "5511444444444@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-retention-race"), Optional.of("Contato"), false, java.util.Map.of()));
        assertEquals(202, post(event("retention-race", jid, "Conteúdo durante purge")).statusCode());

        CountDownLatch insideMessageService = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        MessageService pausedMessages = pausedMessageService(insideMessageService, continueWorker);
        var processor = new EvolutionJobProcessor(jdbc, jobRepository, mapper, pausedMessages, clock, transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var worker = pool.submit(processor::processAvailable);
            assertTrue(insideMessageService.await(5, TimeUnit.SECONDS));
            var purge = pool.submit(() -> new RetentionService(jdbc, transactionManager).purge(Instant.now().plus(Duration.ofDays(8))));
            purge.get(5, TimeUnit.SECONDS);
            assertEquals("PROCESSING", jdbc.queryForObject("select status from processing_jobs", String.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from inbox_events where payload<>'{}'::jsonb", Integer.class));
            continueWorker.countDown();

            assertEquals(1, worker.get(10, TimeUnit.SECONDS));
        } finally {
            continueWorker.countDown();
            pool.shutdownNow();
        }

        assertEquals("COMPLETED", jdbc.queryForObject("select status from processing_jobs", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        jdbc.update("update inbox_events set expires_at=now()-interval '1 second'");
        new RetentionService(jdbc, transactionManager).purge(Instant.now());
        assertEquals("{}", jdbc.queryForObject("select payload::text from inbox_events", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void leaseExpiringDuringSlowMessageEffectsRollsBackThoseEffects() throws Exception {
        String jid = "5511333333333@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-lease-race"), Optional.of("Contato"), false, java.util.Map.of()));
        assertEquals(202, post(event("lease-race", jid, "Conteúdo com lease curto")).statusCode());

        CountDownLatch insideMessageService = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        MessageService pausedMessages = pausedMessageService(insideMessageService, continueWorker);
        java.util.concurrent.atomic.AtomicBoolean firstClaim = new java.util.concurrent.atomic.AtomicBoolean(true);
        JobRepository shortLease = new JobRepository() {
            @Override public List<Job> claim(int limit, Instant now, Duration leaseDuration) {
                if (!firstClaim.getAndSet(false)) return List.of();
                List<Job> claimed = jobRepository.claim(limit, now, Duration.ofMillis(300));
                if (!claimed.isEmpty()) jdbc.update("update processing_jobs set lease_expires_at=now()+interval '300 milliseconds' where job_uuid=?", claimed.getFirst().id());
                return claimed;
            }
            @Override public void complete(UUID jobId, UUID leaseToken) { jobRepository.complete(jobId, leaseToken); }
            @Override public boolean expire(UUID jobId, UUID leaseToken) { return jobRepository.expire(jobId, leaseToken); }
            @Override public boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt) {
                return jobRepository.retry(jobId, leaseToken, now, availableAt);
            }
        };
        var processor = new EvolutionJobProcessor(jdbc, shortLease, mapper, pausedMessages, clock, transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(processor::processAvailable);
            assertTrue(insideMessageService.await(5, TimeUnit.SECONDS));
            Thread.sleep(500);
            continueWorker.countDown();
            assertEquals(0, worker.get(10, TimeUnit.SECONDS));
        } finally {
            continueWorker.countDown();
            pool.shutdownNow();
        }

        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void expiredInboxCannotCommitEffectsAfterDeadlinePassesDuringProcessing() throws Exception {
        String jid = "5511222222222@s.whatsapp.net";
        chatStates.apply(new ChatUpdate(jid, Optional.of(true), Instant.now().minusSeconds(60), true, true,
                Optional.of("snapshot-inbox-deadline"), Optional.of("Contato"), false, java.util.Map.of()));
        assertEquals(202, post(event("inbox-deadline-race", jid, "Conteúdo expirando")).statusCode());
        jdbc.update("update inbox_events set expires_at=now()+interval '300 milliseconds'");

        CountDownLatch insideMessageService = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        MessageService pausedMessages = pausedMessageService(insideMessageService, continueWorker);
        var processor = new EvolutionJobProcessor(jdbc, jobRepository, mapper, pausedMessages, clock, transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var worker = pool.submit(processor::processAvailable);
            assertTrue(insideMessageService.await(5, TimeUnit.SECONDS));
            Thread.sleep(500);
            new RetentionService(jdbc, transactionManager).purge(Instant.now());
            assertEquals("PROCESSING", jdbc.queryForObject("select status from processing_jobs", String.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from inbox_events where payload<>'{}'::jsonb", Integer.class));
            continueWorker.countDown();
            assertEquals(0, worker.get(10, TimeUnit.SECONDS));
        } finally {
            continueWorker.countDown();
            pool.shutdownNow();
        }

        assertEquals("EXPIRED", jdbc.queryForObject("select status from processing_jobs", String.class));
        new RetentionService(jdbc, transactionManager).purge(Instant.now());
        assertEquals("{}", jdbc.queryForObject("select payload::text from inbox_events", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    private MessageService pausedMessageService(CountDownLatch entered, CountDownLatch proceed) {
        return new MessageService(jdbc, transactionManager, identities, watcherProperties, archiveModes) {
            @Override public Acceptance accept(IncomingMessage message) {
                entered.countDown();
                try {
                    if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("message service pause timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("message service pause interrupted", ex);
                }
                return messages.accept(message);
            }
        };
    }

    private HttpResponse<String> post(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/evolution/webhook"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String event(String id, String jid, String message) {
        return """
                {"event":"messages.upsert","instance":"pipeline-test","data":{
                  "type":"notify",
                  "key":{"id":"%s","remoteJid":"%s","fromMe":false},
                  "messageTimestamp":1793008800,"message":{"conversation":"%s"}}}
                """.formatted(id, jid, message);
    }

    private static String evolutionLiveEvent(String id, String jid, String alternate, String content, long timestamp) {
        return """
                {"event":"messages.upsert","instance":"pipeline-test","data":{
                  "key":{"id":"%s","remoteJid":"%s","remoteJidAlt":"%s","fromMe":false},
                  "status":"DELIVERY_ACK","messageType":"conversation",
                  "messageTimestamp":%d,"source":"android",
                  "message":{"conversation":"%s"}}}
                """.formatted(id, jid, alternate, timestamp, content);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTelegramConfiguration {
        @Bean @Primary TelegramTransport fakeTelegramTransport() { return TELEGRAM; }
    }

    private static final class FakeTelegram implements TelegramTransport {
        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicReference<String> lastMessage = new AtomicReference<>("");
        private volatile boolean accepted = true;

        @Override public TelegramDeliveryResult send(String text) {
            sends.incrementAndGet();
            lastMessage.set(text);
            return accepted ? TelegramDeliveryResult.success() : TelegramDeliveryResult.failure();
        }
        private void setAccepted(boolean accepted) { this.accepted = accepted; }
        private String lastMessage() { return lastMessage.get(); }
        private void reset(boolean accepted) { this.accepted = accepted; this.sends.set(0); this.lastMessage.set(""); }
    }
}

package dev.hugo.watcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import dev.hugo.watcher.notification.DisabledEvolutionActionTransport;
import dev.hugo.watcher.notification.EvolutionActionTransport;
import dev.hugo.watcher.notification.IntelligentAlertService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "watcher.runtime.scheduling-enabled=false")
class EvolutionWebhookControllerTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TOKEN = "test-webhook-token";
    private static final String OWNER_JID = "15550000000@s.whatsapp.net";
    private static final String EVENT = """
            {"event":"messages.upsert","instance":"watcher-test","data":{"key":{"id":"evt-1","remoteJid":"120363000000000000@g.us"}}}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.telegram-bot-token", () -> "synthetic-test-token");
        registry.add("watcher.telegram-chat-id", () -> "123456789");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> TOKEN);
        registry.add("watcher.evolution.instance", () -> "watcher-test");
        registry.add("watcher.evolution.max-body-bytes", () -> "1024");
        registry.add("watcher.whatsapp-actions.owner-jid", () -> OWNER_JID);
        registry.add("watcher.telegram.intelligent-alerts-enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EvolutionJobProcessor jobs;
    @Autowired IntelligentAlertService alerts;
    @Autowired EvolutionActionTransport evolutionActionTransport;
    @LocalServerPort int port;

    @BeforeEach
    void clearTables() {
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("delete from whatsapp_owner_message_references");
        jdbc.update("delete from processing_jobs");
        jdbc.update("delete from inbox_events");
        jdbc.update("delete from quarantined_events");
    }

    @Test void onlyBearerAuthenticatedWebhookCanCreateOwnReplyReference() throws Exception {
        assertTrue(evolutionActionTransport instanceof DisabledEvolutionActionTransport,
                "Spring's active Evolution transport must remain disabled in tests and by default");
        String chat = "15551234567@s.whatsapp.net";
        String unauthorized = messageEvent("owner-denied-1", chat, true,
                "{\"conversation\":\"synthetic outbound\"}");
        assertEquals(401, post(unauthorized, null).statusCode());
        assertEquals(0, inboxCount());
        assertEquals(0, jobs.processAvailable());
        assertEquals(0, jdbc.queryForObject("select count(*) from whatsapp_owner_message_references", Integer.class));

        String outbound = messageEvent("owner-outbound-1", chat, true,
                "{\"conversation\":\"synthetic outbound\"}");
        assertEquals(202, post(outbound, TOKEN).statusCode());
        assertEquals(1, jobs.processAvailable());
        assertEquals(1, jdbc.queryForObject("select count(*) from whatsapp_owner_message_references where chat_jid=? and external_id=?",
                Integer.class, chat, "owner-outbound-1"));

        // Classification/decision below is a synthetic audit fixture tied to a reply received over HTTP.
        String reply = messageEvent("reply-to-owner-1", chat, false,
                "{\"extendedTextMessage\":{\"text\":\"synthetic reply\",\"contextInfo\":{\"stanzaId\":\"owner-outbound-1\"}}}");
        assertEquals(202, post(reply, TOKEN).statusCode());
        assertEquals(1, jobs.processAvailable());
        UUID decision = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(86_400);
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('http_alert_test',1,'CRITICAL_URGENCY','Resposta automática: alerta crítico.',true,true) on conflict (template_id,version) do update set enabled=true,identifies_automation=true,body=excluded.body");
        jdbc.update("update automation_settings set ai_classification_enabled=true,intelligent_alerts_enabled=true,kill_switch_enabled=false where singleton_id=1");
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                decision, chat, "", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(expiry));
        String replyPayload = "{\"fromMe\":false,\"content\":{" +
                "\"extendedTextMessage\":{\"text\":\"synthetic reply\",\"contextInfo\":{\"stanzaId\":\"owner-outbound-1\"}}}}";
        jdbc.update("insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload) values (?,?,?,?,?,?::jsonb)",
                decision, chat, "", "reply-to-owner-1", Timestamp.from(now), replyPayload);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,real_action_allowed,decision_state,template_id,classification_status,classification_urgency,classification_confidence,intelligent_alert_reference_hash) values (?,true,false,'synthetic_http_fixture',?,?,true,'WOULD_ACT','http_alert_test','CLASSIFIED','CRITICAL',0.99,?)",
                decision, Timestamp.from(now), Timestamp.from(expiry), hash("reply-to-owner-1"));
        jdbc.update("update simulation_decisions set alert_template_reference='http_alert_test@v1',production_mode=true where batch_id=?", decision);
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) values (?,?,?,?::jsonb)",
                decision, UUID.randomUUID(), Timestamp.from(expiry),
                "{\"provider\":\"gemini\",\"purposes\":[\"CONTACT_REQUEST\",\"CRITICAL_URGENCY\"],\"available\":true,\"requestDetected\":true,\"status\":\"CLASSIFIED\",\"urgency\":\"CRITICAL\",\"confidence\":0.99}");
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,?,'gemini',true,?),(?,?,'gemini',true,?)",
                chat, "CONTACT_REQUEST", Timestamp.from(expiry), chat, "CRITICAL_URGENCY", Timestamp.from(expiry));
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CRITICAL_URGENCY','http_alert_test',1)", chat);
        assertTrue(alerts.enqueue(decision).accepted());
    }

    @Test void rejectsUnauthorizedOriginWithoutCreatingInbox() throws Exception {
        assertEquals(401, post(EVENT, null).statusCode());
        assertEquals(0, inboxCount());
    }

    @Test void rejectsUnauthorizedInstanceWithoutCreatingInbox() throws Exception {
        String wrongInstance = EVENT.replace("watcher-test", "other-instance");
        assertEquals(403, post(wrongInstance, TOKEN).statusCode());
        assertEquals(0, inboxCount());
    }

    @Test void rejectsMalformedJsonWithoutCreatingInbox() throws Exception {
        assertEquals(400, post("{bad json", TOKEN).statusCode());
        assertEquals(0, inboxCount());
    }

    @Test void rejectsOversizedBodyWithoutCreatingInbox() throws Exception {
        String oversized = "{" + " ".repeat(1100) + "}";
        assertEquals(413, post(oversized, TOKEN).statusCode());
        assertEquals(0, inboxCount());
    }

    @Test void returnsDuplicateWithoutCreatingAnotherJob() throws Exception {
        assertEquals(202, post(EVENT, TOKEN).statusCode());
        assertEquals(200, post(EVENT, TOKEN).statusCode());
        assertEquals(1, inboxCount());
        assertEquals(1, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));
    }

    @Test void deduplicatesByEventTypeSoDistinctEventsForOneMessageBothCreateJobs() throws Exception {
        String update = EVENT.replace("messages.upsert", "messages.update");
        assertEquals(202, post(EVENT, TOKEN).statusCode());
        assertEquals(202, post(update, TOKEN).statusCode());
        assertEquals(200, post(EVENT, TOKEN).statusCode());
        assertEquals(2, inboxCount());
        assertEquals(2, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));
    }

    @Test void reportsDatabaseFailureAndDoesNotCreateInbox() throws Exception {
        jdbc.execute("alter table inbox_events rename to inbox_events_disabled");
        try {
            assertEquals(503, post(EVENT, TOKEN).statusCode());
        } finally {
            jdbc.execute("alter table inbox_events_disabled rename to inbox_events");
        }
        assertEquals(0, inboxCount());
    }

    @Test void reportsSuccessOnlyAfterInboxAndJobAreCommitted() throws Exception {
        assertEquals(202, post(EVENT, TOKEN).statusCode());
        assertEquals(1, inboxCount());
        assertEquals(1, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));
    }

    @Test void durablyQuarantinesChatUpdateWithoutStableIdInsteadOfInventingSnapshot() throws Exception {
        String update = """
                {"event":"chats.update","instance":"watcher-test","data":{
                  "remoteJid":"120363000000000000@g.us","archived":true}}
                """;

        assertEquals(202, post(update, TOKEN).statusCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where event_kind='CHAT_STATE' and source_event_id is null and reason='unverified_or_incomplete_state'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_snapshot", Integer.class));
        assertEquals(0, inboxCount());
    }

    private HttpResponse<String> post(String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/evolution/webhook"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int inboxCount() {
        return jdbc.queryForObject("select count(*) from inbox_events", Integer.class);
    }

    private static String messageEvent(String id, String chat, boolean fromMe, String messageJson) {
        return """
                {"event":"messages.upsert","instance":"watcher-test","data":{"type":"notify","key":{"id":"%s","remoteJid":"%s","fromMe":%s},"messageTimestamp":%d,"message":%s}}
                """.formatted(id, chat, fromMe, Instant.now().getEpochSecond(), messageJson);
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

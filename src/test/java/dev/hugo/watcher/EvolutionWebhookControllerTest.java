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

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvolutionWebhookControllerTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TOKEN = "test-webhook-token";
    private static final String EVENT = """
            {"event":"messages.upsert","instance":"watcher-test","data":{"key":{"id":"evt-1","remoteJid":"120363000000000000@g.us"}}}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.n8n-url", () -> "http://n8n:5678/webhook");
        registry.add("watcher.n8n-secret", () -> "n8n-test-secret");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> TOKEN);
        registry.add("watcher.evolution.instance", () -> "watcher-test");
        registry.add("watcher.evolution.max-body-bytes", () -> "1024");
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeEach
    void clearTables() {
        jdbc.update("delete from processing_jobs");
        jdbc.update("delete from inbox_events");
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

    private HttpResponse<String> post(String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/evolution/webhook"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int inboxCount() {
        return jdbc.queryForObject("select count(*) from inbox_events", Integer.class);
    }
}

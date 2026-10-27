package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.Authenticator;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "watcher.runtime.scheduling-enabled=false")
@Import(EvolutionPipelineIntegrationTest.TestN8nConfiguration.class)
class EvolutionPipelineIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TOKEN = "pipeline-test-token";
    private static final FakeN8n N8N = new FakeN8n();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.n8n-url", () -> "http://n8n:5678/webhook");
        registry.add("watcher.n8n-secret", () -> "local-test-secret");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> TOKEN);
        registry.add("watcher.evolution.instance", () -> "pipeline-test");
        registry.add("watcher.evolution.max-body-bytes", () -> "4096");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EvolutionJobProcessor jobs;
    @Autowired JobRepository jobRepository;
    @Autowired ChatStateService chatStates;
    @Autowired NotificationService notifications;
    @LocalServerPort int port;

    @BeforeEach void resetLocalState() {
        jdbc.execute("truncate table outbox_actions, pending_notification, processed_message, quarantined_events, processing_jobs, inbox_events, chat_snapshot, contact_identity, identity_aliases restart identity cascade");
        N8N.reset(200);
    }

    @AfterAll static void stopFakeN8n() { N8N.stop(); }

    @Test void duplicateWebhookSurvivesWorkerRestartAndOutboxRetryBeforeFakeN8nAcknowledges() throws Exception {
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

        N8N.setStatus(503);
        notifications.flush(Instant.now());
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions", String.class));
        assertEquals(1, jdbc.queryForObject("select attempts from outbox_actions", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));

        N8N.setStatus(200);
        jdbc.update("update outbox_actions set available_at=now()-interval '1 second'");
        notifications.flush(Instant.now());

        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertTrue(N8N.lastBody().contains("Olá"));
    }

    @Test void retriesDatabaseFailureAndQuarantinesMessageWhenArchiveSnapshotIsUnknown() throws Exception {
        String jid = "5511888888888@s.whatsapp.net";
        assertEquals(202, post(event("unclassified", jid, "Tipo ausente").replace("\"type\":\"notify\",", "")).statusCode());
        jobs.processAvailable();
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where source_event_id='unclassified' and reason='unverified_message_metadata'", Integer.class));

        assertEquals(202, post(event("unknown-state", jid, "Sem snapshot")).statusCode());
        jobs.processAvailable();
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where reason='archive_state_unknown_or_unsynchronized'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));

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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestN8nConfiguration {
        @Bean @Primary N8nClient localFakeN8n(WatcherProperties properties) {
            return new N8nClient(properties, new RedirectingHttpClient(N8N.url()), Duration.ofSeconds(2),
                    List.of(Duration.ZERO, Duration.ZERO));
        }
    }

    private static final class FakeN8n {
        private final com.sun.net.httpserver.HttpServer server;
        private volatile int status = 200;
        private volatile String lastBody = "";

        private FakeN8n() {
            try {
                server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/webhook", exchange -> {
                    lastBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
                server.start();
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Could not start local fake n8n", ex);
            }
        }

        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void setStatus(int status) { this.status = status; }
        private String lastBody() { return lastBody; }
        private void reset(int status) { this.status = status; this.lastBody = ""; }
        private void stop() { server.stop(0); }
    }

    private static final class RedirectingHttpClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final String baseUrl;

        private RedirectingHttpClient(String baseUrl) { this.baseUrl = baseUrl; }
        @Override public java.util.Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public java.util.Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public java.util.Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public java.util.Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public java.util.Optional<Executor> executor() { return delegate.executor(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws java.io.IOException, InterruptedException {
            return delegate.send(redirect(request), handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return delegate.sendAsync(redirect(request), handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                           HttpResponse.PushPromiseHandler<T> pushHandler) {
            return delegate.sendAsync(redirect(request), handler, pushHandler);
        }
        private HttpRequest redirect(HttpRequest request) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + request.uri().getRawPath()))
                    .timeout(request.timeout().orElse(Duration.ofSeconds(5)));
            request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            return builder.method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
        }
    }
}

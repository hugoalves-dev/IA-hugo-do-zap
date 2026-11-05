package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class BudgetedProviderTransportTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    BudgetService budgets;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from ai_budget_reservations");
        jdbc.update("delete from ai_budget_limits");
        budgets = new BudgetService(jdbc, new DataSourceTransactionManager(source), new SimpleMeterRegistry());
    }
    @Test void persistsDispatchBeforeHttpAndHoldsUnknownBilling() {
        limits(10);
        var transport = guarded((request, timeout, max) -> {
            assertEquals("DISPATCHED", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
            return new ProviderTransport.Response(200, "{}");
        }, () -> true);
        transport.send(request(), Duration.ofSeconds(1), 1024);
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
        assertEquals(10L, jdbc.queryForObject("select reserved_micros from ai_budget_reservations", Long.class));
    }
    @Test void absentBudgetOrRevokedConsentNeverCallsHttp() {
        AtomicInteger calls = new AtomicInteger();
        ProviderTransport fake = (request, timeout, max) -> { calls.incrementAndGet(); return new ProviderTransport.Response(200, "{}"); };
        assertThrows(RuntimeException.class, () -> guarded(fake, () -> true).send(request(), Duration.ofSeconds(1), 1024));
        limits(10);
        assertThrows(RuntimeException.class, () -> guarded(fake, () -> false).send(request(), Duration.ofSeconds(1), 1024));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from ai_budget_reservations", Integer.class));
    }
    @Test void ambiguousTimeoutNeverFreesBudgetForRetry() {
        limits(10);
        AtomicInteger calls = new AtomicInteger();
        var transport = guarded((request, timeout, max) -> { calls.incrementAndGet(); throw new RuntimeException("timeout"); }, () -> true);
        assertThrows(RuntimeException.class, () -> transport.send(request(), Duration.ofSeconds(1), 1024));
        assertThrows(RuntimeException.class, () -> transport.send(request(), Duration.ofSeconds(1), 1024));
        assertEquals(1, calls.get());
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
    }
    @Test void promptContextSeparatesTransportInvocationFromUncertainReservation() {
        limits(10);
        UUID jobId = UUID.fromString("50000000-0000-0000-0000-000000000050");
        AtomicInteger calls = new AtomicInteger();
        var contextType = assertDoesNotThrow(() -> Class.forName("dev.hugo.watcher.ai.PromptGenerationContext"));
        var context = assertDoesNotThrow(() -> contextType.getConstructor(UUID.class, String.class)
                .newInstance(jobId, "REFINE"));
        var constructor = assertDoesNotThrow(() -> BudgetedProviderTransport.class.getConstructor(
                ProviderTransport.class, BudgetService.class, java.util.function.BooleanSupplier.class,
                String.class, long.class, Clock.class, contextType));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BudgetedProviderTransport.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var transport = (ProviderTransport) constructor.newInstance((ProviderTransport) (request, timeout, max) -> {
                calls.incrementAndGet();
                return new ProviderTransport.Response(503, "provider response secret");
            }, budgets, (java.util.function.BooleanSupplier) () -> true, "openrouter", 10,
                    Clock.systemUTC(), context);
            transport.send(request(), Duration.ofSeconds(1), 1024);
        } catch (ReflectiveOperationException exception) {
            fail("prompt-generation provider context must be supported", exception);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }

        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(1, calls.get());
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
        assertTrue(messages.stream().anyMatch(message -> message.contains("TRANSPORT_INVOKED")
                && message.contains(jobId.toString())), messages.toString());
        assertTrue(messages.stream().anyMatch(message -> message.contains("HTTP_RESPONSE_RECEIVED")
                && message.contains("503")), messages.toString());
        assertTrue(messages.stream().noneMatch(message -> message.contains("provider response secret")));
    }
    @Test void confirmedOpenRouterCostSettlesButGeminiUsageIsNotBilling() {
        limits(30);
        var transport = new BudgetedProviderTransport((request, timeout, max) -> new ProviderTransport.Response(200,
                "{\"usage\":{\"cost\":0.000004}}"), budgets, () -> true, "openrouter", 10, Clock.systemUTC());
        transport.send(request(), Duration.ofSeconds(1), 1024);
        assertEquals("SETTLED", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
        assertEquals(4L, jdbc.queryForObject("select actual_micros from ai_budget_reservations", Long.class));
    }
    @Test void concurrentHttpAttemptsCannotPassTheSameRemainingBudget() throws Exception {
        limits(10); AtomicInteger calls = new AtomicInteger();
        var transport = guarded((request,timeout,max) -> { calls.incrementAndGet(); return new ProviderTransport.Response(200,"{}"); }, () -> true);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> work = () -> { start.await(); try { transport.send(request(),Duration.ofSeconds(1),1024); return true; } catch (BudgetExceededException blocked) { return false; } };
            var first = pool.submit(work); var second = pool.submit(work); start.countDown();
            assertNotEquals(first.get(),second.get());
        }
        assertEquals(1,calls.get());
    }
    @Test void surroundingRollbackCannotEraseDispatchedCost() {
        limits(10);
        var outer = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(RuntimeException.class, () -> outer.executeWithoutResult(status -> {
            guarded((request,timeout,max) -> new ProviderTransport.Response(200,"{}"), () -> true).send(request(),Duration.ofSeconds(1),1024);
            throw new RuntimeException("decision persistence failed");
        }));
        assertEquals("UNCERTAIN",jdbc.queryForObject("select status from ai_budget_reservations",String.class));
    }
    private BudgetedProviderTransport guarded(ProviderTransport fake, java.util.function.BooleanSupplier gate) {
        return new BudgetedProviderTransport(fake, budgets, gate, "gemini", 10, Clock.systemUTC());
    }
    private void limits(long amount) { jdbc.update("insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros) values (1,?,?)", amount, amount); }
    private HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://example.invalid")).GET().build(); }
}

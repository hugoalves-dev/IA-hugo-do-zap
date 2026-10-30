package dev.hugo.watcher.telegram;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class CallbackTokenServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private CallbackTokenService tokens;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"));

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from telegram_admin_callback_tokens");
        tokens = new CallbackTokenService(jdbc, clock);
    }

    @Test void tokenCanBeConsumedOnlyOnceByTheBoundUserAndPrivateChat() {
        String token = tokens.issue(1001, 2002, new CallbackAction("ocupado", java.util.List.of("30")), clock.instant().plus(5, ChronoUnit.MINUTES));

        assertThrows(CallbackTokenRejectedException.class, () -> tokens.consume(token, 1001, 9999));
        assertEquals(new CallbackAction("ocupado", java.util.List.of("30")), tokens.consume(token, 1001, 2002));
        assertThrows(CallbackTokenRejectedException.class, () -> tokens.consume(token, 1001, 2002));
    }

    @Test void expiredTokenCannotBeConsumed() {
        String token = tokens.issue(1001, 2002, new CallbackAction("disponivel", java.util.List.of("15")), clock.instant().plusSeconds(30));
        clock.advance(31);

        assertThrows(CallbackTokenRejectedException.class, () -> tokens.consume(token, 1001, 2002));
        assertEquals(0, jdbc.queryForObject("select count(*) from telegram_admin_callback_tokens where consumed_at is not null", Integer.class));
    }

    @Test void onlyTokenHashIsPersisted() {
        String token = tokens.issue(1001, 2002, new CallbackAction("ferias", java.util.List.of("2026-12-01", "2026-12-03")), clock.instant().plusSeconds(60));

        String stored = jdbc.queryForObject("select token_hash from telegram_admin_callback_tokens", String.class);

        assertNotEquals(token, stored);
        assertEquals(64, stored.length());
    }

    @Test void concurrentConsumersCannotBothUseOneToken() throws Exception {
        String token = tokens.issue(1001, 2002, new CallbackAction("simulacao", java.util.List.of("off")), clock.instant().plusSeconds(60));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> consumeAfter(start, token));
            var second = pool.submit(() -> consumeAfter(start, token));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
    }

    @Test void issuingNewConfirmationPurgesExpiredTokenPayloads() {
        tokens.issue(1001, 2002, new CallbackAction("perfil", java.util.List.of("chat", "WORK_ONLY")), clock.instant().plusSeconds(30));
        clock.advance(31);

        tokens.issue(1001, 2002, new CallbackAction("perfil", java.util.List.of("chat", "ANYTIME")), clock.instant().plusSeconds(60));

        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_callback_tokens", Integer.class));
    }

    @Test void cancelRevokesOnlyPairedConfirmationForBoundUserAndChat() {
        String confirmation = tokens.issue(1001, 2002, new CallbackAction("ocupado", java.util.List.of("30")),
                clock.instant().plusSeconds(60));
        String unrelated = tokens.issue(1001, 2002, new CallbackAction("simulacao", java.util.List.of("off")),
                clock.instant().plusSeconds(60));
        assertFalse(tokens.invalidate(confirmation, 9999, 2002));
        assertFalse(tokens.invalidate(confirmation, 1001, 9999));
        assertTrue(tokens.invalidate(confirmation, 1001, 2002));
        assertFalse(tokens.invalidate(confirmation, 1001, 2002));
        assertThrows(CallbackTokenRejectedException.class, () -> tokens.consume(confirmation, 1001, 2002));
        assertEquals("simulacao", tokens.consume(unrelated, 1001, 2002).command());
    }

    private boolean consumeAfter(CountDownLatch start, String token) throws Exception {
        start.await();
        try { tokens.consume(token, 1001, 2002); return true; }
        catch (CallbackTokenRejectedException used) { return false; }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        private void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}

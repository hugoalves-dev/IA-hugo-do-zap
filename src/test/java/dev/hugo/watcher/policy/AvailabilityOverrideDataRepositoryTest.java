package dev.hugo.watcher.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "watcher.runtime.scheduling-enabled=false")
class AvailabilityOverrideDataRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("watcher.telegram-bot-token", () -> "synthetic-test-token");
        registry.add("watcher.telegram-chat-id", () -> "123456789");
        registry.add("watcher.group-jids", () -> "120363000000000000@g.us");
        registry.add("watcher.evolution.webhook-token", () -> "startup-test-token");
        registry.add("watcher.evolution.instance", () -> "watcher-startup-test");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AvailabilityOverrideDataRepository springData;
    @Autowired GlobalPolicyRepository policy;
    @Autowired PersistedPolicyRepository persistedPolicies;

    @BeforeEach void clear() {
        jdbc.update("delete from availability_overrides");
    }

    @Test void readsExistingFlywayRowWithoutSchemaChanges() {
        Instant start = Instant.parse("2026-11-02T12:00:00Z");
        Instant end = Instant.parse("2026-11-02T14:00:00Z");
        jdbc.update("""
                insert into availability_overrides(singleton_id,state,starts_at,expires_at)
                values (1,'AVAILABLE',?,?)
                """, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));

        AvailabilityOverrideRow row = springData.findById((short) 1).orElseThrow();
        assertEquals((short) 1, row.singletonId());
        assertEquals("AVAILABLE", row.state());
        assertEquals(start, row.startsAt().toInstant());
        assertEquals(end, row.expiresAt().toInstant());
        assertEquals(new TemporaryAvailabilityOverride(AvailabilityState.AVAILABLE, start, end),
                policy.temporaryAvailabilityOverride().orElseThrow());
    }

    @Test void upsertIsIdempotentAndPreservesSingletonIdentity() {
        Instant start = Instant.parse("2026-11-02T12:00:00Z");
        Instant end = Instant.parse("2026-11-02T14:00:00Z");
        policy.saveTemporaryAvailabilityOverride(
                new TemporaryAvailabilityOverride(AvailabilityState.UNAVAILABLE, start, end));
        policy.saveTemporaryAvailabilityOverride(
                new TemporaryAvailabilityOverride(AvailabilityState.AVAILABLE, start.plusSeconds(60), end.plusSeconds(60)));

        assertEquals(1, jdbc.queryForObject("select count(*) from availability_overrides", Integer.class));
        assertEquals(AvailabilityState.AVAILABLE, policy.temporaryAvailabilityOverride().orElseThrow().state());
        assertEquals(start.plusSeconds(60), policy.temporaryAvailabilityOverride().orElseThrow().startsAt());
    }


    @Test void managedPolicyResolverUsesTheSpringDataAvailabilityOverride() {
        Instant start = Instant.parse("2026-11-02T12:00:00Z");
        Instant end = Instant.parse("2026-11-02T14:00:00Z");
        policy.saveTemporaryAvailabilityOverride(new TemporaryAvailabilityOverride(
                AvailabilityState.UNAVAILABLE, start, end));

        EffectivePolicy resolved = persistedPolicies.forChat("phase-1-fixture@s.whatsapp.net", "ANYTIME");
        assertEquals(new TemporaryAvailabilityOverride(AvailabilityState.UNAVAILABLE, start, end),
                resolved.temporaryAvailability());
    }

    @Test void databaseRejectsInvalidStateAndTimeRange() {
        OffsetDateTime start = Instant.parse("2026-11-02T12:00:00Z").atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusHours(1);
        assertThrows(DataIntegrityViolationException.class,
                () -> springData.upsert("UNKNOWN", start, end));
        assertThrows(DataIntegrityViolationException.class,
                () -> springData.upsert("AVAILABLE", end, start));
        assertTrue(policy.temporaryAvailabilityOverride().isEmpty());
    }
}

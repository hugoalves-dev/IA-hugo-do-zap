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
        jdbc.update("delete from chat_policy_overrides");
        jdbc.update("delete from identity_aliases");
        jdbc.update("delete from holiday_calendar");
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

    @Autowired HolidayService holidays;
    @Autowired GlobalPolicyDataRepository globalData;
    @Autowired PolicyProfileDataRepository profileData;
    @Autowired ChatPolicyOverrideDataRepository overrideData;

    @Test void managedPoliciesReadJsonbAndRespectExplicitOverrides() {
        assertTrue(globalData.loadFields().orElseThrow().contains("includeOptionalHolidays"));
        assertTrue(profileData.loadFields("WORK_ONLY").orElseThrow().contains("WORK_WINDOW"));

        jdbc.update("""
                insert into chat_policy_overrides(chat_jid,profile_name,fields)
                values ('issue24-policy@lid','WORK_ONLY','{"includeOptionalHolidays":true}'::jsonb)
                """);
        EffectivePolicy effective = persistedPolicies.forChat("issue24-policy@lid", "ANYTIME");
        assertEquals(ScheduleMode.WORK_WINDOW, effective.value(PolicyField.MODE));
        assertEquals(PolicySource.PROFILE, effective.origin(PolicyField.MODE));
        assertEquals(true, effective.value(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS));
        assertEquals(PolicySource.CHAT_OVERRIDE, effective.origin(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS));
    }

    @Test void conflictingAliasPoliciesFailClosedButEqualAliasesRemainEquivalent() {
        String canonical = "issue24-canonical@lid";
        String first = "issue24-first@s.whatsapp.net";
        String second = "issue24-second@s.whatsapp.net";
        jdbc.update("insert into identity_aliases(alias,canonical_identity) values (?,?),(?,?)",
                first, canonical, second, canonical);
        jdbc.update("""
                insert into chat_policy_overrides(chat_jid,profile_name,fields)
                values (?, 'ANYTIME', '{}'::jsonb), (?, 'WORK_ONLY', '{}'::jsonb)
                """, first, second);

        assertTrue(persistedPolicies.forChat(canonical, "ANYTIME").fields().isEmpty(),
                "different policy values on identity aliases must fail closed");

        jdbc.update("update chat_policy_overrides set profile_name='ANYTIME' where chat_jid=?", second);
        EffectivePolicy resolved = persistedPolicies.forChat(canonical, "WORK_ONLY");
        assertEquals(ScheduleMode.ANYTIME, resolved.value(PolicyField.MODE));
        assertEquals(1, overrideData.forCanonicalChat(canonical, canonical).size(),
                "same policies on two aliases are one DISTINCT policy");
    }

    @Test void holidayProjectionPreservesAttestationAndLocalJurisdictionPriority() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 12, 31);
        java.sql.Timestamp validatedAt = java.sql.Timestamp.from(Instant.parse("2026-11-02T12:00:00Z"));
        jdbc.update("""
                insert into holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status,
                                             optional,validated_by,validated_at)
                values (?,'SP','State','https://official.example/sp',2026,'VALIDATED',false,'test-maintainer',?),
                       (?,'LINS','Local','https://official.example/lins',2026,'VALIDATED',false,'test-maintainer',?)
                """, day, validatedAt, day, validatedAt);
        var matches = holidays.on(day);
        assertEquals(2, matches.size());
        assertEquals("LINS", matches.getFirst().jurisdiction());
        assertEquals("SP", matches.get(1).jurisdiction());
        assertTrue(matches.stream().allMatch(HolidayEntry::hasManualAttestation));
        assertEquals(validatedAt.toInstant(), matches.getFirst().validatedAt());
    }
}

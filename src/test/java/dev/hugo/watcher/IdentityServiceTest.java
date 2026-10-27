package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class IdentityServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private IdentityService identities;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from identity_aliases");
        identities = new IdentityService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test void doesNotJoinLidAndPhoneWithoutExplicitEvidence() {
        var lid = identities.observe(new IdentityObservation("1001@lid",
                Set.of("5511999999999@s.whatsapp.net"), IdentityEvidence.NONE));
        var phone = identities.observe(new IdentityObservation("5511999999999@s.whatsapp.net",
                Set.of(), IdentityEvidence.NONE));

        assertEquals("1001@lid", lid.canonicalJid());
        assertNotEquals(lid.canonicalJid(), phone.canonicalJid());
        assertEquals(Set.of("1001@lid"), lid.aliases());
        assertEquals(2, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
    }

    @Test void joinsLidAndPhoneOnlyWhenTheObservationCarriesExplicitPairEvidence() {
        var linked = identities.observe(new IdentityObservation("1001@lid",
                Set.of("5511999999999@s.whatsapp.net"), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));
        var samePhone = identities.observe(new IdentityObservation("5511999999999@s.whatsapp.net",
                Set.of(), IdentityEvidence.NONE));

        assertEquals(linked.canonicalJid(), samePhone.canonicalJid());
        assertEquals(2, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
    }

    @Test void differentGroupParticipantsRemainDifferentIdentities() {
        var firstSender = identities.observe(new IdentityObservation("1001@lid", Set.of(), IdentityEvidence.NONE));
        var secondSender = identities.observe(new IdentityObservation("1002@lid", Set.of(), IdentityEvidence.NONE));

        assertNotEquals(firstSender.canonicalJid(), secondSender.canonicalJid());
    }
}

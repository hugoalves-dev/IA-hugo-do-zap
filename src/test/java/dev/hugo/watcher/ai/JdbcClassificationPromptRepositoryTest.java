package dev.hugo.watcher.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcClassificationPromptRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private JdbcClassificationPromptRepository repository;

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        repository = new JdbcClassificationPromptRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @Test void activePointerIsOptimisticAndRevisionHistoryIsImmutable() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        PromptRevision draft = repository.createDraft("Only an operational note.", ClassificationPromptRepository.contentHash("Only an operational note."), 1001, now);
        repository.activate(draft.revision(), 0, 1001, now.plusSeconds(1));

        assertEquals(draft.body(), repository.active().orElseThrow().body());
        assertThrows(dev.hugo.watcher.telegram.RuntimeSettingVersionConflictException.class,
                () -> repository.activate(draft.revision(), 0, 1001, now.plusSeconds(2)));
        assertEquals(1, repository.activeVersion());
        assertEquals(1, jdbc.queryForObject("select count(*) from classification_prompt_state_history", Integer.class));
    }

    @Test void promptInputSessionIsBoundToPrivateIdentityAndConsumedOnce() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        repository.beginInput(1001, 2002, now.plusSeconds(600), now);
        assertTrue(repository.hasPendingInput(1001, 2002, now.plusSeconds(1)));
        assertFalse(repository.hasPendingInput(1002, 2002, now.plusSeconds(1)));

        PromptRevision draft = repository.createDraftFromInput(1001, 2002, "Sensitive admin text.", ClassificationPromptRepository.contentHash("Sensitive admin text."), now.plusSeconds(2));
        assertEquals("Sensitive admin text.", draft.body());
        assertFalse(repository.hasPendingInput(1001, 2002, now.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.createDraftFromInput(1001, 2002, "Replay.", ClassificationPromptRepository.contentHash("Replay."), now.plusSeconds(4)));
        assertEquals(1, jdbc.queryForObject("select count(*) from classification_prompt_revisions", Integer.class));
    }

    @Test void generationLookupReturnsOwnedExpiredDraftOnly() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into classification_prompt_drafts
                    (id,owner_id,base_revision,body,origin,description,status,refinement_count,version,expires_at)
                values (?,44,null,'Safe test prompt.','manual','', 'editing',0,2,?)
                """, id, Timestamp.from(now.minusSeconds(1)));

        var method = assertDoesNotThrow(() -> repository.getClass().getMethod(
                "draftForGeneration", UUID.class, long.class),
                "generation needs an owner-scoped lookup that can report expiry");
        var expired = assertDoesNotThrow(() -> (Optional<?>) method.invoke(repository, id, 44L));
        var anotherOwnersDraft = assertDoesNotThrow(() -> (Optional<?>) method.invoke(repository, id, 45L));

        assertTrue(expired.isPresent());
        assertTrue(anotherOwnersDraft.isEmpty());
        assertTrue(repository.draft(id, 44, now).isEmpty(), "existing draft lookup must keep its expiry filter");
    }
}

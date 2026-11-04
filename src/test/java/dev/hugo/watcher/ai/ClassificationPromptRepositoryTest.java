package dev.hugo.watcher.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ClassificationPromptRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private JdbcTemplate jdbc;
    private ClassificationPromptRepository repository;

    @BeforeEach void setup() {
        String schema = "prompt_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        String jdbcUrl = postgres.getJdbcUrl();
        source.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        jdbc = new JdbcTemplate(source);
        assertEquals(schema, jdbc.queryForObject("select current_schema()", String.class));
        repository = new JdbcClassificationPromptRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private ClassificationPromptRepository.Revision revision(String body) {
        return repository.insertRevision(new ClassificationPromptRepository.Revision(0, body,
                ClassificationPromptRepository.contentHash(body), "manual", "test revision", 42, NOW));
    }

    @Test void draftsSurviveRepositoryRestartAndRejectStaleForeignAndExpiredEdits() {
        UUID id = UUID.randomUUID();
        repository.createDraft(new ClassificationPromptRepository.Draft(id, 42, null, "first", "manual", "", "editing", 0, 0, NOW.plusSeconds(60)));
        assertTrue(repository.appendDraftPart(id, 42, 0, "second", NOW));
        assertFalse(repository.appendDraftPart(id, 42, 0, "stale", NOW));
        assertFalse(repository.appendDraftPart(id, 43, 1, "foreign", NOW));
        var stored = new JdbcClassificationPromptRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource())).draft(id, 42, NOW).orElseThrow();
        assertEquals("first\nsecond", stored.body());
        assertEquals(1, stored.version());
        assertTrue(repository.draft(id, 43, NOW).isEmpty());
        assertTrue(repository.draft(id, 42, NOW.plusSeconds(60)).isEmpty());
        assertFalse(repository.saveProposal(id, 42, 1, "expired", "ai_assisted", "proposal", NOW.plusSeconds(60)));
        assertTrue(repository.saveProposal(id, 42, 1, "proposal", "ai_assisted", "description", NOW));
        stored = repository.draft(id, 42, NOW).orElseThrow();
        assertEquals(0, stored.refinementCount());
        assertEquals("description", stored.description());
        assertEquals("ai_assisted", stored.origin());
        assertTrue(repository.setDraftStatus(id, 42, 2, "cancelled"));
        assertFalse(repository.appendDraftPart(id, 42, 3, "late", NOW));
        assertFalse(repository.setDraftStatus(id, 42, 2, "activated"));
    }

    @Test void transitionsActivateResetRestoreAndRejectStaleState() {
        assertNull(repository.activeState().revisionId());
        var first = revision("one");
        var second = revision("two");
        assertEquals(first, repository.revision(first.id()).orElseThrow());
        assertTrue(repository.transition(null, first.id(), "activate", 42, NOW));
        assertFalse(repository.transition(null, second.id(), "activate", 42, NOW));
        assertTrue(repository.transition(first.id(), second.id(), "activate", 42, NOW));
        assertTrue(repository.transition(second.id(), first.id(), "restore", 42, NOW));
        assertTrue(repository.transition(first.id(), null, "reset", 42, NOW));
        assertNull(repository.activeState().revisionId());
        var history = repository.history(10);
        assertEquals(4, history.size());
        assertEquals("reset", history.getFirst().event());
        assertNull(history.getFirst().nextRevision());
        assertEquals(first.id(), history.getFirst().previousRevision());
        assertEquals("restore", history.get(1).event());
        assertEquals(2, repository.history(2).size());
    }

    @Test void invalidHistoryRollsBackActivePointerAndRevisionsAreImmutable() {
        var first = revision("one");
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.transition(null, first.id(), "invalid", 42, NOW));
        assertNull(repository.activeState().revisionId());
        assertTrue(repository.history(10).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update classification_prompt_revisions set prompt_body='changed' where revision=?", first.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "delete from classification_prompt_revisions where revision=?", first.id()));
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertRevision(
                new ClassificationPromptRepository.Revision(0, "x", "invalid", "manual", "", 42, NOW)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into classification_prompt_state(singleton_id) values (2)"));
    }

    @Test void databaseRejectsInvalidMetadataAndOversizedDrafts() {
        UUID id = UUID.randomUUID();
        repository.createDraft(new ClassificationPromptRepository.Draft(id, 42, null, "", "manual", "",
                "editing", 0, 0, NOW.plusSeconds(60)));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.appendDraftPart(id, 42, 0, "x".repeat(16001), NOW));
        assertEquals(0, repository.draft(id, 42, NOW).orElseThrow().version());
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveProposal(id, 42, 0, "text", "unknown", "", NOW));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.setDraftStatus(id, 42, 0, "unknown"));
        var first = revision("one");
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.transition(null, first.id(), "reset", 42, NOW));
        assertNull(repository.activeState().revisionId());
        for (int i = 0; i < 4; i++) {
            assertTrue(repository.saveProposal(id, 42, i, "proposal", "ai_assisted", "", NOW));
        }
        assertFalse(repository.saveProposal(id, 42, 4, "extra", "ai_assisted", "", NOW));
        assertEquals(3, repository.draft(id, 42, NOW).orElseThrow().refinementCount());
        assertThrows(IllegalArgumentException.class, () -> repository.history(101));
    }

    @Test void concurrentActivationsHaveOneWinnerAndOneHistoryRow() throws Exception {
        var first = revision("one");
        var second = revision("two");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return repository.transition(null, first.id(), "activate", 42, NOW); });
            var b = workers.submit(() -> { start.await(); return repository.transition(null, second.id(), "activate", 42, NOW); });
            start.countDown();
            assertNotEquals(a.get(), b.get());
        }
        assertEquals(1, repository.history(10).size());
        assertEquals(repository.activeState().revisionId(), repository.history(10).getFirst().nextRevision());
    }
}

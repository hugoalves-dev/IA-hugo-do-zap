package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class LegacyImportServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir Path tempDir;

    @Test void importingTwiceIsIdempotentAndLeavesTheSnapshotByteForByteUnchanged() throws Exception {
        JdbcTemplate jdbc = freshTarget();
        Path snapshot = createSnapshot("legacy-pn@s.whatsapp.net", "legacy-lid@lid", true);
        byte[] originalBytes = Files.readAllBytes(snapshot);
        LegacyImportService importer = importer(jdbc);

        ImportReport first = importer.importSnapshot(snapshot, ImportMode.APPLY);
        ImportReport second = importer.importSnapshot(snapshot, ImportMode.APPLY);

        assertEquals(1, first.importedIdentities());
        assertEquals(1, first.importedPendings());
        assertEquals(0, first.conflicts().size());
        assertEquals(0, second.importedIdentities());
        assertEquals(0, second.importedPendings());
        assertEquals(2, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from contact_identity", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_snapshot", Integer.class),
                "legacy archived state must never be treated as the current Evolution snapshot");
        assertEquals(HexFormat.of().formatHex(originalBytes), HexFormat.of().formatHex(Files.readAllBytes(snapshot)));
    }

    @Test void dryRunReportsCandidatesWithoutWritingToPostgres() throws Exception {
        JdbcTemplate jdbc = freshTarget();
        Path snapshot = createSnapshot("dry-run-pn@s.whatsapp.net", "dry-run-lid@lid", false);

        ImportReport report = importer(jdbc).importSnapshot(snapshot, ImportMode.DRY_RUN);

        assertTrue(report.dryRun());
        assertEquals(1, report.plannedIdentities());
        assertEquals(1, report.plannedPendings());
        assertEquals(0, report.importedIdentities());
        assertEquals(0, report.importedPendings());
        assertEquals(0, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void aliasConflictIsReportedAndFailsClosedWithoutImportingLegacyArchiveStateOrPending() throws Exception {
        JdbcTemplate jdbc = freshTarget();
        jdbc.update("insert into identity_aliases(alias,canonical_identity) values (?,?)",
                "legacy-pn@s.whatsapp.net", "current-lid@lid");
        Path snapshot = createSnapshot("legacy-pn@s.whatsapp.net", "legacy-lid@lid", true);

        ImportReport report = importer(jdbc).importSnapshot(snapshot, ImportMode.APPLY);

        assertFalse(report.conflicts().isEmpty());
        assertEquals("current-lid@lid", jdbc.queryForObject(
                "select canonical_identity from identity_aliases where alias=?", String.class, "legacy-pn@s.whatsapp.net"));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_snapshot", Integer.class));
    }

    @Test void uniqueIdentityAnchorConflictIsReportedInsteadOfEscapingAsASqlError() throws Exception {
        JdbcTemplate jdbc = freshTarget();
        jdbc.update("insert into contact_identity(canonical_jid,phone_jid,updated_at) values (?,?,?)",
                "current-lid@lid", "legacy-pn@s.whatsapp.net", java.sql.Timestamp.from(Instant.now()));
        Path snapshot = createSnapshot("legacy-pn@s.whatsapp.net", "legacy-lid@lid", false);

        ImportReport report = importer(jdbc).importSnapshot(snapshot, ImportMode.APPLY);

        assertEquals(1, report.conflicts().size());
        assertEquals("current-lid@lid", jdbc.queryForObject(
                "select canonical_jid from contact_identity where phone_jid=?", String.class, "legacy-pn@s.whatsapp.net"));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void pendingInsertedAfterPreflightWithDifferentDueTimeIsReportedAndRolledBack() throws Exception {
        JdbcTemplate base = freshTarget();
        Path snapshot = createSnapshot("race-pn@s.whatsapp.net", "race-lid@lid", false);
        boolean[] inserted = {false};
        JdbcTemplate interleaved = new JdbcTemplate(base.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                if (!inserted[0] && sql.startsWith("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at)")) {
                    inserted[0] = true;
                    base.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                            args[0], args[1], Timestamp.from(((Timestamp) args[2]).toInstant().minusSeconds(1)), args[3], args[4]);
                }
                return super.update(sql, args);
            }
        };

        ImportReport report = importer(interleaved).importSnapshot(snapshot, ImportMode.APPLY);

        assertTrue(inserted[0]);
        assertEquals(1, report.conflicts().size());
        assertEquals(0, report.importedPendings());
        assertEquals(0, base.queryForObject("select count(*) from pending_notification", Integer.class),
                "the transaction must roll back the interleaved conflicting pending row");
    }

    private JdbcTemplate freshTarget() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from identity_aliases");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from chat_snapshot");
        return jdbc;
    }

    private LegacyImportService importer(JdbcTemplate jdbc) {
        return new LegacyImportService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private Path createSnapshot(String phoneJid, String lidJid, boolean archived) throws Exception {
        Path file = tempDir.resolve("watcher.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("create table contact_identity(canonical_jid text primary key,lid_jid text unique,phone_jid text unique,username text,display_name text,display_name_source text,updated_at text not null)");
            statement.execute("create table chat_state(jid text primary key,archived integer not null,display_name text,is_group integer not null,updated_at text not null,notification_policy text not null default 'ANYTIME')");
            statement.execute("create table pending_notification(jid text primary key,message_count integer not null,first_message_at text not null,last_message_at text not null,last_message_preview text)");
            try (var insert = connection.prepareStatement("insert into contact_identity values(?,?,?,?,?,?,?)")) {
                insert.setString(1, phoneJid); insert.setString(2, lidJid); insert.setString(3, phoneJid);
                insert.setString(4, null); insert.setString(5, "Pessoa Legada"); insert.setString(6, "legacy");
                insert.setString(7, Instant.now().toString()); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("insert into chat_state values(?,?,?,?,?,?)")) {
                insert.setString(1, phoneJid); insert.setInt(2, archived ? 1 : 0); insert.setString(3, "Pessoa Legada");
                insert.setInt(4, 0); insert.setString(5, Instant.now().toString()); insert.setString(6, "ANYTIME");
                insert.executeUpdate();
            }
            Instant first = Instant.now().minusSeconds(3600);
            Instant last = Instant.now().minusSeconds(60);
            try (var insert = connection.prepareStatement("insert into pending_notification values(?,?,?,?,?)")) {
                insert.setString(1, phoneJid); insert.setInt(2, 2); insert.setString(3, first.toString());
                insert.setString(4, last.toString()); insert.setString(5, "prévia sintética"); insert.executeUpdate();
            }
        }
        return file;
    }
}

package dev.hugo.watcher;

import org.sqlite.SQLiteConfig;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Read-only legacy SQLite snapshot adapter; never imports legacy archive flags. */
@Component
public class LegacySqliteSnapshotReader {
    public SourceData read(Path snapshot) throws SQLException {
        try (Connection connection = openReadOnly(snapshot)) {
            return readSource(connection);
        }
    }

    private static Connection openReadOnly(Path snapshot) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot.toAbsolutePath(), config.toProperties());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private static SourceData readSource(Connection connection) throws SQLException {
        requireColumns(connection, "contact_identity", Set.of("canonical_jid", "lid_jid", "phone_jid", "username", "display_name", "display_name_source", "updated_at"));
        requireColumns(connection, "pending_notification", Set.of("jid", "message_count", "first_message_at", "last_message_at", "last_message_preview"));
        requireColumns(connection, "chat_state", Set.of("jid", "is_group", "notification_policy"));
        List<SourceIdentity> identities = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                     from contact_identity order by canonical_jid
                     """)) {
            while (rows.next()) identities.add(new SourceIdentity(rows.getString("canonical_jid"), rows.getString("lid_jid"),
                    rows.getString("phone_jid"), rows.getString("username"), rows.getString("display_name"),
                    rows.getString("display_name_source"), rows.getString("updated_at")));
        }
        List<SourcePending> pending = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select p.jid,p.message_count,p.first_message_at,p.last_message_at,p.last_message_preview,
                            c.is_group,c.notification_policy
                     from pending_notification p left join chat_state c on c.jid=p.jid order by p.jid
                     """)) {
            while (rows.next()) pending.add(new SourcePending(rows.getString("jid"), rows.getInt("message_count"),
                    rows.getString("first_message_at"), rows.getString("last_message_at"), rows.getString("last_message_preview"),
                    rows.getObject("is_group") == null ? null : rows.getInt("is_group"), rows.getString("notification_policy")));
        }
        return new SourceData(identities, pending);
    }

    private static void requireColumns(Connection connection, String table, Set<String> required) throws SQLException {
        Set<String> actual = new TreeSet<>();
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) actual.add(rows.getString("name"));
        }
        if (!actual.containsAll(required)) throw new SQLException("Unsupported legacy schema in " + table);
    }


    public record SourceData(List<SourceIdentity> identities, List<SourcePending> pending) { }
    public record SourceIdentity(String canonicalJid, String lidJid, String phoneJid, String username,
                                  String displayName, String displayNameSource, String updatedAt) {
        Set<String> aliases() {
            Set<String> result = new TreeSet<>();
            if (canonicalJid != null) result.add(canonicalJid);
            if (lidJid != null) result.add(lidJid);
            if (phoneJid != null) result.add(phoneJid);
            return result;
        }
    }
    public record SourcePending(String jid, int messageCount, String firstMessageAt, String lastMessageAt,
                                 String preview, Integer isGroup, String notificationPolicy) { }

}

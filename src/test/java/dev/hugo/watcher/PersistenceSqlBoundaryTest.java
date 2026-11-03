package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #24: production JDBC/SQL operations belong to repositories.
 * Scan every source file, including unannotated helpers, not only @Service classes.
 */
class PersistenceSqlBoundaryTest {
    private static final Pattern JDBC_INVOCATION = Pattern.compile(
            "\\b(?:jdbc|jdbcTemplate|jdbcClient|jdbcOperations|jdbcOps|named|namedJdbc|"
            + "namedParameterJdbcTemplate|statement|stmt|conn|connection|dataSource)\\s*\\.\\s*"
            + "(?:update|query\\w*|execute\\w*|batchUpdate|call|sql|prepareStatement|createStatement|getConnection)\\s*\\(");
    private static final Pattern OWNED_CLIENT = Pattern.compile(
            "\\b(?:private|protected)\\s+(?:static\\s+)?(?:final\\s+)?"
            + "(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|DataSource|Connection)\\s+\\w+");
    private static final Pattern RAW_JDBC = Pattern.compile(
            "\\b(?:DriverManager|DataSourceUtils)\\s*\\.\\s*getConnection\\s*\\(");
    private static final Pattern SQL_LITERAL = Pattern.compile(
            "(?is)\"{1,3}\\s*(?:select\\b|insert\\s+into\\b|"
            + "update\\s+\\w+\\s+set\\b|delete\\s+from\\b|pragma\\b)");

    @Test
    void noProductionJdbcOrSqlLiteralsOutsideRepositories() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "A source checkout is required for SQL-boundary checks");
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (file.getFileName().toString().endsWith("Repository.java")
                        || source.matches("(?s).*@Repository\\b.*")) continue;
                String location = root.relativize(file).toString();
                if (JDBC_INVOCATION.matcher(source).find())
                    violations.add(location + ": JDBC method invocation");
                if (OWNED_CLIENT.matcher(source).find())
                    violations.add(location + ": owns a JDBC client or connection");
                if (RAW_JDBC.matcher(source).find())
                    violations.add(location + ": opens a raw SQL connection");
                if (SQL_LITERAL.matcher(source).find())
                    violations.add(location + ": SQL statement literal");
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Only repositories may own SQL/JDBC operations: " + violations);
    }

    @Test
    void legacyEligibilityAdapterMustNotReappear() {
        assertFalse(Files.exists(Path.of("src", "main", "java", "dev", "hugo", "watcher",
                "notification", "DecisionEligibilityJdbcReader.java")));
    }
}

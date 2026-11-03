package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cheap structural backstop for issue #24. PostgreSQL-native operations remain
 * permitted in @Repository classes, but SQL must not leak back into orchestration.
 */
class PersistenceSqlBoundaryTest {
    private static final Pattern COMPONENT = Pattern.compile(
            "(?m)^\\s*@(Service|Component|Controller|RestController)(?:\\s*\\(|\\s*$)");
    private static final Pattern DIRECT_JDBC = Pattern.compile(
            "\\bjdbc\\s*\\.\\s*(?:update|query(?:ForObject|ForList|ForMap|ForRowSet)?|execute|batchUpdate|call)\\s*\\(");
    private static final Pattern OWNED_JDBC = Pattern.compile(
            "\\bprivate\\s+(?:final\\s+)?(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)\\s+\\w+");

    @Test
    void componentsMustDelegateSqlToRepositories() throws IOException {
        Path root = Path.of("src", "main", "java", "dev", "hugo", "watcher");
        assertTrue(Files.isDirectory(root), "Source tree is required for the persistence boundary gate");
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!COMPONENT.matcher(source).find()) continue;
                if (DIRECT_JDBC.matcher(source).find())
                    violations.add(root.relativize(file) + ": direct JdbcTemplate query/update/execute call");
                if (OWNED_JDBC.matcher(source).find())
                    violations.add(root.relativize(file) + ": component owns a JDBC client field");
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Persist SQL in @Repository classes, not services/controllers: " + violations);
    }
}

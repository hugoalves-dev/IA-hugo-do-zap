package dev.hugo.watcher;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** One-shot CLI entry point enabled only when an operator supplies a snapshot path. */
@Component
@ConditionalOnProperty(prefix = "watcher.legacy-import", name = "snapshot")
public class LegacyImportRunner implements ApplicationRunner {
    private static final System.Logger LOG = System.getLogger(LegacyImportRunner.class.getName());

    private final LegacyImportService importer;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public LegacyImportRunner(LegacyImportService importer, Environment environment,
                              ConfigurableApplicationContext context) {
        this.importer = importer;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String snapshotValue = environment.getRequiredProperty("watcher.legacy-import.snapshot");
            if (snapshotValue.isBlank()) {
                throw new IllegalArgumentException("watcher.legacy-import.snapshot must not be empty");
            }
            Path snapshot = Path.of(snapshotValue);
            ImportMode mode = environment.getProperty("watcher.legacy-import.mode", ImportMode.class, ImportMode.DRY_RUN);
            ImportReport report = importer.importSnapshot(snapshot, mode);
            LOG.log(System.Logger.Level.INFO,
                    "Legacy snapshot import finished: mode={0}, identities={1}, pending={2}, plannedIdentities={3}, plannedPending={4}, unchanged={5}, expired={6}, conflicts={7}",
                    mode, report.importedIdentities(), report.importedPendings(), report.plannedIdentities(),
                    report.plannedPendings(), report.unchanged(),
                    report.skippedExpired(), report.conflicts().size());
            if (!report.conflicts().isEmpty()) {
                throw new IllegalStateException("Legacy snapshot import reported conflicts; no rows were applied");
            }
        } finally {
            context.close();
        }
    }
}

package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyImportRunnerTest {
    @Test void rejectsEmptySnapshotAndClosesContextWithoutCallingImporter() {
        LegacyImportService importer = mock(LegacyImportService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        MockEnvironment environment = new MockEnvironment().withProperty("watcher.legacy-import.snapshot", "");

        assertThrows(IllegalArgumentException.class,
                () -> new LegacyImportRunner(importer, environment, context).run(new DefaultApplicationArguments()));
        verify(context).close();
    }

    @Test void runsOnlyWithExplicitSnapshotAndClosesTheOneShotContext() throws Exception {
        LegacyImportService importer = mock(LegacyImportService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("watcher.legacy-import.snapshot", "C:/migration-copy/watcher.db")
                .withProperty("watcher.legacy-import.mode", "DRY_RUN");
        when(importer.importSnapshot(Path.of("C:/migration-copy/watcher.db"), ImportMode.DRY_RUN))
                .thenReturn(new ImportReport(0, 0, 0, 0, 0, 0, List.of(), true));

        new LegacyImportRunner(importer, environment, context).run(new DefaultApplicationArguments());

        verify(importer).importSnapshot(Path.of("C:/migration-copy/watcher.db"), ImportMode.DRY_RUN);
        verify(context).close();
    }

    @Test void stopsWithAnErrorWhenTheImportReportsConflicts() throws Exception {
        LegacyImportService importer = mock(LegacyImportService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("watcher.legacy-import.snapshot", "C:/migration-copy/watcher.db")
                .withProperty("watcher.legacy-import.mode", "APPLY");
        when(importer.importSnapshot(Path.of("C:/migration-copy/watcher.db"), ImportMode.APPLY))
                .thenReturn(new ImportReport(0, 0, 0, 0, 0, 0, List.of(new ImportConflict("pending", "abc123", "target_pending_differs")), false));

        assertThrows(IllegalStateException.class,
                () -> new LegacyImportRunner(importer, environment, context).run(new DefaultApplicationArguments()));
        verify(context).close();
    }
}

package dev.hugo.watcher;

import java.util.List;

/** Result of an explicit snapshot import. Counts written are zero in DRY_RUN mode. */
public record ImportReport(int importedIdentities, int importedPendings, int plannedIdentities,
                           int plannedPendings, int unchanged, int skippedExpired,
                           List<ImportConflict> conflicts, boolean dryRun) {
    public ImportReport {
        conflicts = List.copyOf(conflicts);
    }
}

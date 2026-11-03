package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;

/** A missing owner identity never establishes an action-enabling reference. */
class PersonalReferenceEvidencePersistenceBoundaryTest {
    @Test
    void missingOwnerDoesNotReadEvidenceOrGrantPermission() {
        var persistence = new PersonalReferenceEvidenceRepository(null);
        assertNull(PersonalReferenceEvidence.findHash(persistence, UUID.randomUUID(), "", java.time.Instant.now()));
    }
}

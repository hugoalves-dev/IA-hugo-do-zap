package dev.hugo.watcher.simulation;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Read-only queue timing projection used for non-blocking telemetry. */
public interface SimulationBatchDueRepository extends Repository<SimulationBatchDueRow, UUID> {
    @Query("select due_at from simulation_batches where batch_id = :batchId")
    Optional<OffsetDateTime> findDueAt(@Param("batchId") UUID batchId);
}

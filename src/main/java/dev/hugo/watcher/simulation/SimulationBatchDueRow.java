package dev.hugo.watcher.simulation;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only identity for simulation_batches, whose Flyway schema is authoritative. */
@Table("simulation_batches")
public record SimulationBatchDueRow(@Id @Column("batch_id") UUID batchId,
                                    @Column("due_at") OffsetDateTime dueAt) { }

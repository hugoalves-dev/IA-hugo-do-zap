package dev.hugo.watcher.notification;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Flyway-owned simulation_decisions row identity for a read-only repository query. */
@Table("simulation_decisions")
public record DecisionEligibilityRow(@Id @Column("batch_id") UUID batchId) { }

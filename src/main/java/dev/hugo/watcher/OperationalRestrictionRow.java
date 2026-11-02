package dev.hugo.watcher;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Flyway V30 singleton, deliberately read-only from ordinary runtime code. */
@Table("operational_restrictions")
public record OperationalRestrictionRow(
        @Id @Column("singleton_id") Integer singletonId,
        @Column("emergency_latched") Boolean emergencyLatched,
        @Column("simulation_paused") Boolean simulationPaused) { }

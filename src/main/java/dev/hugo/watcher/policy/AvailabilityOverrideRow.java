package dev.hugo.watcher.policy;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * Read model for the existing Flyway-managed singleton table.
 * Writes use a PostgreSQL atomic upsert, never Spring Data's save() insert/update heuristic.
 */
@Table("availability_overrides")
public record AvailabilityOverrideRow(
        @Id @Column("singleton_id") Short singletonId,
        String state,
        @Column("starts_at") OffsetDateTime startsAt,
        @Column("expires_at") OffsetDateTime expiresAt,
        @Column("updated_at") OffsetDateTime updatedAt) {
}

package dev.hugo.watcher.policy;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Spring Data JDBC repository for the existing Flyway V13 singleton.
 * Native upsert is intentional: concurrent admin requests must never race on insert.
 */
public interface AvailabilityOverrideDataRepository extends Repository<AvailabilityOverrideRow, Short> {
    Optional<AvailabilityOverrideRow> findById(Short singletonId);

    @Modifying
    @Query("""
            insert into availability_overrides(singleton_id, state, starts_at, expires_at, updated_at)
            values (1, :state, :startsAt, :expiresAt, now())
            on conflict (singleton_id) do update
                set state=excluded.state,
                    starts_at=excluded.starts_at,
                    expires_at=excluded.expires_at,
                    updated_at=now()
            """)
    int upsert(@Param("state") String state,
               @Param("startsAt") OffsetDateTime startsAt,
               @Param("expiresAt") OffsetDateTime expiresAt);
}

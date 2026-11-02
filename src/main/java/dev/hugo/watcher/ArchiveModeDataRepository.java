package dev.hugo.watcher;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Typed Spring Data JDBC operations on Flyway V11's singleton. */
public interface ArchiveModeDataRepository extends Repository<ArchiveMonitorSettingsRow, Short> {
    @Query("select mode from archive_monitor_settings where singleton_id=1")
    String readMode();

    @Modifying
    @Query("update archive_monitor_settings set mode=:mode,updated_at=now() where singleton_id=1")
    int updateMode(@Param("mode") String mode);
}

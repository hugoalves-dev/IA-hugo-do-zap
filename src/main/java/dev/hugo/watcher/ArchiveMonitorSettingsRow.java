package dev.hugo.watcher;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("archive_monitor_settings")
public record ArchiveMonitorSettingsRow(@Id @Column("singleton_id") Short singletonId,
                                        String mode) { }

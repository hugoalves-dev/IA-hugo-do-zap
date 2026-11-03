package dev.hugo.watcher.policy;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Flyway V13 owns the schema. Read-only mapping; JSONB is read as text via explicit query. */
@Table("global_policy")
public record GlobalPolicyRow(@Id @Column("singleton_id") Short singletonId, String fields) { }

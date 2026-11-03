package dev.hugo.watcher.policy;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Existing VARCHAR primary key. JSONB fields are deliberately read with ::text. */
@Table("policy_profiles")
public record PolicyProfileRow(@Id @Column("profile_name") String profileName, String fields) { }

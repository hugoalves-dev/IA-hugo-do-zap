package dev.hugo.watcher.policy;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Existing chat_jid primary key; actual canonical/alias policy reads use a distinct projection. */
@Table("chat_policy_overrides")
public record ChatPolicyOverrideRow(
        @Id @Column("chat_jid") String chatJid,
        @Column("profile_name") String profileName,
        String fields) { }

package dev.hugo.watcher.telegram;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Existing Flyway V34 JSONB inbox, represented solely for named Spring Data JDBC statements. */
@Table("telegram_webhook_inbox")
public record TelegramWebhookRow(@Id @Column("update_id") Long updateId, String payload) { }

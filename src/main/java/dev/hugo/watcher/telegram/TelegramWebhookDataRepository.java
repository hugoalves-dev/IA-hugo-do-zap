package dev.hugo.watcher.telegram;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The incoming webhook is durably ACKed only after this atomic insert commits. */
public interface TelegramWebhookDataRepository extends Repository<TelegramWebhookRow, Long> {
    @Modifying
    @Query("""
            insert into telegram_webhook_inbox(update_id,payload)
            values (:updateId, cast(:payload as jsonb))
            on conflict (update_id) do nothing
            """)
    int insertIfAbsent(@Param("updateId") long updateId, @Param("payload") String payload);
}

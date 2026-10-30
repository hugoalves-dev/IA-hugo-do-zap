package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.simulation.Batch;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads only retained, previously captured messages for the same conversation. */
final class ConversationContextRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Duration window;
    ConversationContextRepository(JdbcTemplate jdbc, Duration window) {
        if (window == null || window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("context window must be between zero and seven days");
        this.jdbc = jdbc;
        this.window = window;
    }

    ClassificationInput input(Batch batch, Instant now) {
        ClassificationInput target = ClassificationInput.fromBatch(batch);
        if (target.target().isEmpty()) return target;
        if (batch.messages().isEmpty() || batch.chatJid().endsWith("@g.us") && batch.participantJid().isBlank())
            return target;
        Instant first = batch.messages().stream().map(IncomingMessage::receivedAt).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant latest = batch.messages().stream().filter(m -> !m.fromMe() && !m.history())
                .map(IncomingMessage::receivedAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (first == null || latest == null) return ClassificationInput.fromBatch(batch);
        List<String> rows = jdbc.query("""
                select m.payload::text from simulation_batch_messages m
                  join simulation_batches b on b.batch_id=m.batch_id
                 where m.chat_jid=? and m.participant_jid=? and m.batch_id<>?
                   and m.received_at<? and m.received_at>=? and b.expires_at>?
                 order by m.received_at desc,m.external_id desc limit 5
                """, (rs, row) -> rs.getString(1), batch.chatJid(), batch.participantJid(), batch.id(),
                Timestamp.from(first), Timestamp.from(latest.minus(window)), Timestamp.from(now));
        List<IncomingMessage> combined = new ArrayList<>();
        for (String row : rows) combined.add(JSON.readValue(row, IncomingMessage.class));
        combined.addAll(batch.messages());
        return ClassificationInput.fromBatch(new Batch(batch.id(), batch.chatJid(), batch.participantJid(), combined));
    }
}

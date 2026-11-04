package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.IdentityService;
import dev.hugo.watcher.simulation.Batch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads only retained, previously captured messages for the same conversation. */
@Repository
public class ConversationContextRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Duration window;
    private final IdentityService identities;
    @Autowired public ConversationContextRepository(JdbcTemplate jdbc, IdentityService identities) {
        this(jdbc, identities, Duration.ofHours(24));
    }

    public ConversationContextRepository(JdbcTemplate jdbc) {
        this(jdbc, null, Duration.ofHours(24));
    }

    ConversationContextRepository(JdbcTemplate jdbc, Duration window) {
        this(jdbc, null, window);
    }

    ConversationContextRepository(JdbcTemplate jdbc, IdentityService identities, Duration window) {
        if (window == null || window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("context window must be between zero and seven days");
        this.jdbc = jdbc;
        this.identities = identities;
        this.window = window;
    }

    ClassificationInput input(Batch batch, Instant now) {
        return input(batch, now, window);
    }

    public ClassificationInput input(Batch batch, Instant now, Duration window) {
        if (window == null || window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("context window must be between zero and seven days");
        ClassificationInput target = input(batch, false);
        if (target.target().isEmpty()) return target;
        if (batch.messages().isEmpty() || batch.chatJid().endsWith("@g.us") && batch.participantJid().isBlank())
            return target;
        Instant first = batch.messages().stream().map(IncomingMessage::receivedAt).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant latest = batch.messages().stream().filter(m -> !m.fromMe() && !m.history() && !ClassificationInput.extractText(m.content()).isBlank())
                .map(IncomingMessage::receivedAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (first == null || latest == null) return input(batch, false);
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
        if (!batch.chatJid().endsWith("@g.us")) {
            combined.addAll(jdbc.query("""
                    select external_id,received_at,text from simulation_owner_context
                     where chat_jid=? and received_at<? and received_at>=? and expires_at>?
                     order by received_at desc,external_id desc limit 5
                    """, (rs, row) -> new IncomingMessage(java.util.Optional.of(rs.getString(1)), batch.chatJid(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), true, "notify", false,
                    java.util.Map.of("conversation", rs.getString(3)), rs.getTimestamp(2).toInstant()), batch.chatJid(),
                    Timestamp.from(latest), Timestamp.from(latest.minus(window)), Timestamp.from(now)));
        } else {
            combined.addAll(jdbc.query("""
                    select o.external_id,o.received_at,o.text from simulation_owner_context o
                      join simulation_batch_messages replied
                        on replied.chat_jid=o.chat_jid and replied.external_id=o.reply_to_external_id
                      join simulation_batches source_batch on source_batch.batch_id=replied.batch_id
                     where o.chat_jid=? and replied.participant_jid=? and o.received_at<?
                       and o.received_at>=? and o.expires_at>? and source_batch.expires_at>?
                     order by o.received_at desc,o.external_id desc limit 5
                    """, (rs, row) -> new IncomingMessage(java.util.Optional.of(rs.getString(1)), batch.chatJid(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), true, "notify", false,
                    java.util.Map.of("conversation", rs.getString(3)), rs.getTimestamp(2).toInstant()), batch.chatJid(),
                    batch.participantJid(), Timestamp.from(latest), Timestamp.from(latest.minus(window)),
                    Timestamp.from(now), Timestamp.from(now)));
        }
        combined.addAll(batch.messages());
        combined.sort(Comparator.comparing(IncomingMessage::receivedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(m -> m.externalId().orElse("")));
        return input(new Batch(batch.id(), batch.chatJid(), batch.participantJid(), combined), true);
    }

    private ClassificationInput input(Batch batch, boolean includeVerifiedGroupOwnerContext) {
        if (identities == null) return includeVerifiedGroupOwnerContext
                ? ClassificationInput.fromContext(batch) : ClassificationInput.fromBatch(batch);
        java.util.function.Function<String, ClassificationInput.SenderIdentity> resolver = jid ->
                new ClassificationInput.SenderIdentity(identities.canonicalJidFor(jid), identities.resolveFriendlyDisplayName(jid));
        return includeVerifiedGroupOwnerContext
                ? ClassificationInput.fromContext(batch, resolver) : ClassificationInput.fromBatch(batch, resolver);
    }
}

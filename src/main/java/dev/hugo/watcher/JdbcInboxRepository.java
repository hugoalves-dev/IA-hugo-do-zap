package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInboxRepository implements InboxRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Acceptance accept(EvolutionEvent event) {
        final String payload;
        try {
            payload = json.writeValueAsString(event.payload());
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Evolution event could not be encoded", ex);
        }

        Long inboxId = jdbc.query("""
                        insert into inbox_events(source, event_type, external_id, payload)
                        values (?, ?, ?, ?::jsonb)
                        on conflict (source, external_id) do nothing
                        returning id
                        """,
                results -> results.next() ? results.getLong(1) : null,
                event.source(), event.eventType(), event.externalId(), payload);
        if (inboxId == null) return Acceptance.DUPLICATE;

        jdbc.update("insert into processing_jobs(inbox_event_id, status) values (?, 'READY')", inboxId);
        return Acceptance.NEW;
    }
}

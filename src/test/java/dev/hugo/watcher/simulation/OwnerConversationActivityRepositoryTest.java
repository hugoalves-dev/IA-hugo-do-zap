package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerConversationActivityRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-10T20:26:52Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OwnerConversationActivityRepository repository = new OwnerConversationActivityRepository(jdbc);

    @Test void canonicalChatWithOwnerMessageWithinSixtyMinutesIsActive() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(NOW.minus(Duration.ofMinutes(5))));

        assertEquals(OwnerConversationActivity.ACTIVE,
                repository.activity("canonical@s.whatsapp.net", NOW, Duration.ofMinutes(60)));
        verify(jdbc).query(contains("identity_aliases"), any(RowMapper.class), any(), any(), any());
    }

    @Test void absentOrExpiredOwnerEventIsInactiveAndInvalidIdentityIsUnknown() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(Collections.singletonList(null));

        assertEquals(OwnerConversationActivity.INACTIVE,
                repository.activity("canonical@s.whatsapp.net", NOW, Duration.ofMinutes(60)));
        assertEquals(OwnerConversationActivity.UNKNOWN,
                repository.activity(" ", NOW, Duration.ofMinutes(60)));
    }
}

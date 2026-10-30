package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class TelegramWebhookControllerTest {
    private final TelegramAdminProperties properties = new TelegramAdminProperties();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final String secret = "aB_012345678901234567890123456789xyZ";

    private TelegramWebhookController controller() {
        properties.setWebhookSecret(secret);
        return new TelegramWebhookController(properties, jdbc);
    }

    @Test void rejectsMissingOrWrongSecretWithoutTouchingDatabase() {
        var endpoint = controller();
        assertEquals(401, endpoint.receive(null, validPayload()).getStatusCode().value());
        assertEquals(401, endpoint.receive("wrong-secret", validPayload()).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsMalformedPayloadAndMissingUpdateId() {
        var endpoint = controller();
        assertEquals(400, endpoint.receive(secret, "{").getStatusCode().value());
        assertEquals(400, endpoint.receive(secret, "{\"message\":{}}").getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test void acksOnlyAfterDurableInsertAndDeduplicatesReplay() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        var endpoint = controller();
        assertEquals(202, endpoint.receive(secret, validPayload()).getStatusCode().value());
        assertEquals(200, endpoint.receive(secret, validPayload()).getStatusCode().value());
        verify(jdbc, times(2)).update(anyString(), aryEq(new Object[]{42L, validPayload()}));
    }

    @Test void returnsRetryableFailureWhenPersistenceFails() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("test database down"));
        assertEquals(503, controller().receive(secret, validPayload()).getStatusCode().value());
    }

    private static String validPayload() {
        return "{\"update_id\":42,\"message\":{\"message_id\":4,\"chat\":{\"id\":2002,\"type\":\"private\"},\"from\":{\"id\":1001},\"text\":\"/status\"}}";
    }
}

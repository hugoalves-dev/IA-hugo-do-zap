package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramWebhookControllerTest {
    private final TelegramAdminProperties properties = new TelegramAdminProperties();
    private final TelegramWebhookDataRepository inbox = mock(TelegramWebhookDataRepository.class);
    private final String secret = "aB_012345678901234567890123456789xyZ";

    private TelegramWebhookController controller() {
        properties.setWebhookSecret(secret);
        return new TelegramWebhookController(properties, inbox);
    }

    @Test void rejectsMissingOrWrongSecretWithoutTouchingDatabase() {
        var endpoint = controller();
        assertEquals(401, endpoint.receive(null, validPayload()).getStatusCode().value());
        assertEquals(401, endpoint.receive("wrong-secret", validPayload()).getStatusCode().value());
        verifyNoInteractions(inbox);
    }

    @Test void rejectsMalformedPayloadAndMissingUpdateId() {
        var endpoint = controller();
        assertEquals(400, endpoint.receive(secret, "{").getStatusCode().value());
        assertEquals(400, endpoint.receive(secret, "{\"message\":{}}").getStatusCode().value());
        verifyNoInteractions(inbox);
    }

    @Test void acksOnlyAfterDurableInsertAndDeduplicatesReplay() {
        when(inbox.insertIfAbsent(42L, validPayload())).thenReturn(1, 0);
        var endpoint = controller();
        assertEquals(202, endpoint.receive(secret, validPayload()).getStatusCode().value());
        assertEquals(200, endpoint.receive(secret, validPayload()).getStatusCode().value());
        verify(inbox, times(2)).insertIfAbsent(42L, validPayload());
    }

    @Test void returnsRetryableFailureWhenPersistenceFails() {
        when(inbox.insertIfAbsent(42L, validPayload()))
                .thenThrow(new DataAccessResourceFailureException("test database down"));
        assertEquals(503, controller().receive(secret, validPayload()).getStatusCode().value());
    }

    private static String validPayload() {
        return "{\"update_id\":42,\"message\":{\"message_id\":4,\"chat\":{\"id\":2002,\"type\":\"private\"},\"from\":{\"id\":1001},\"text\":\"/status\"}}";
    }
}

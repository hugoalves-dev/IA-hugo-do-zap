package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionWebhookRabbitIngressTest {
    private static final String BODY = """
            {"event":"messages.upsert","instance":"watcher-test","data":{"key":{"id":"evt-42","remoteJid":"15551234567@s.whatsapp.net"}}}
            """;

    @Test
    void returnsAcceptedOnlyAfterPublishingEvolutionEventToRabbit() {
        var inbox = mock(InboxRepository.class);
        var publisher = mock(RabbitEventPublisher.class);
        var controller = controller(inbox, publisher);

        var response = controller.receive("Bearer test-token", BODY);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(publisher).publish("incoming", "evt-42", BODY);
        verifyNoInteractions(inbox);
    }

    @Test
    void asksEvolutionToRetryWhenRabbitPublicationCannotBeConfirmed() {
        var inbox = mock(InboxRepository.class);
        var publisher = mock(RabbitEventPublisher.class);
        doThrow(new IllegalStateException("confirm timeout"))
                .when(publisher).publish("incoming", "evt-42", BODY);
        var controller = controller(inbox, publisher);

        var response = controller.receive("Bearer test-token", BODY);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(publisher).publish("incoming", "evt-42", BODY);
        verifyNoInteractions(inbox);
    }

    private static EvolutionWebhookController controller(InboxRepository inbox, RabbitEventPublisher publisher) {
        var properties = new EvolutionWebhookProperties();
        properties.setWebhookToken("test-token");
        properties.setInstance("watcher-test");
        return new EvolutionWebhookController(inbox, properties, mock(ConnectionService.class), publisher);
    }
}

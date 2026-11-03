package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitEventPublisherTest {
    @Test
    void publishesPersistentMessageOnlyAfterPositiveConfirm() {
        RabbitTemplate template = confirmedTemplate(true);
        RabbitEventPublisher publisher = new RabbitEventPublisher(template, properties());
        UUID eventId = UUID.randomUUID();

        assertDoesNotThrow(() -> publisher.publish("incoming", eventId.toString(), "{\"event\":\"messages.upsert\"}"));

        var processor = org.mockito.ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("watcher.events"), eq("incoming"), anyString(), processor.capture(), any(CorrelationData.class));
        Message message = processor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
        assertEquals(eventId.toString(), message.getMessageProperties().getMessageId());
    }

    @Test
    void rejectsNegativePublisherConfirm() {
        RabbitEventPublisher publisher = new RabbitEventPublisher(confirmedTemplate(false), properties());

        assertThrows(IllegalStateException.class, () -> publisher.publish("incoming", "evt-1", "{}"));
    }

    private static RabbitTemplate confirmedTemplate(boolean ack) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? "confirmed" : "rejected"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        return template;
    }

    private static RabbitMessagingProperties properties() {
        RabbitMessagingProperties properties = new RabbitMessagingProperties();
        properties.setEnabled(true);
        properties.setExchange("watcher.events");
        properties.setConfirmTimeout(Duration.ofSeconds(1));
        return properties;
    }
}

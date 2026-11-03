package dev.hugo.watcher;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes durable events and returns only after RabbitMQ confirms routing. */
@Component
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitEventPublisher {
    private final RabbitTemplate rabbit;
    private final RabbitMessagingProperties properties;

    public RabbitEventPublisher(RabbitTemplate rabbit, RabbitMessagingProperties properties) {
        this.rabbit = Objects.requireNonNull(rabbit);
        this.properties = Objects.requireNonNull(properties);
    }

    public void publish(String routingKey, String messageId, String json) {
        publish(routingKey, messageId, json, java.util.Map.of(), null);
    }

    public void publish(String routingKey, String messageId, String json, java.util.Map<String, Object> headers) {
        publish(routingKey, messageId, json, headers, null);
    }

    public void publishDelayed(String routingKey, String messageId, String json,
                               java.util.Map<String, Object> headers, long delayMillis) {
        if (delayMillis < 1) throw new IllegalArgumentException("delay must be positive");
        publish(routingKey, messageId, json, headers, delayMillis);
    }

    private void publish(String routingKey, String messageId, String json,
                         java.util.Map<String, Object> headers, Long delayMillis) {
        if (!properties.isEnabled()) throw new IllegalStateException("RabbitMQ ingress is disabled");
        if (routingKey == null || routingKey.isBlank()) throw new IllegalArgumentException("routing key is required");
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("message ID is required");
        if (json == null || json.isBlank()) throw new IllegalArgumentException("message body is required");

        CorrelationData correlation = new CorrelationData(messageId);
        java.util.Map<String, Object> eventHeaders = new java.util.HashMap<>(headers);
        eventHeaders.putIfAbsent("x-ingress-at", RabbitDeliveryContext.ingressAt().toString());
        rabbit.convertAndSend(properties.getExchange(), routingKey, json, message -> {
            var messageProperties = message.getMessageProperties();
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            messageProperties.setContentType("application/json");
            messageProperties.setMessageId(messageId);
            messageProperties.getHeaders().putAll(eventHeaders);
            if (delayMillis != null) messageProperties.setExpiration(Long.toString(delayMillis));
            return message;
        }, correlation);

        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ did not route the event");
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ rejected the event");
        } catch (TimeoutException ex) {
            throw new IllegalStateException("RabbitMQ publisher confirm timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ publisher confirm was interrupted", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("RabbitMQ publisher confirm failed", ex.getCause());
        }
    }
}

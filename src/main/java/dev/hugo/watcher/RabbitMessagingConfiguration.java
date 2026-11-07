package dev.hugo.watcher;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableConfigurationProperties(RabbitMessagingProperties.class)
@ConditionalOnProperty(prefix = "watcher.rabbitmq", name = "enabled", havingValue = "true")
class RabbitMessagingConfiguration {
    private static final System.Logger LOG = System.getLogger(RabbitMessagingConfiguration.class.getName());
    static final String INCOMING_QUEUE = "watcher.incoming";
    static final String ADMIN_QUEUE = "watcher.admin";
    static final String NOTIFICATION_QUEUE = "watcher.notifications";
    static final String PROMPT_RESULT_QUEUE = "watcher.prompt-results";
    static final String DEAD_LETTER_QUEUE = "watcher.dead-letter";
    static final String DEAD_LETTER_EXCHANGE = "watcher.dead-letter";

    @Bean DirectExchange watcherEventExchange(RabbitMessagingProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean DirectExchange watcherDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> LOG.log(System.Logger.Level.ERROR,
                "RabbitMQ returned an unroutable event: exchange={0}, routingKey={1}, replyCode={2}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode()));
        return template;
    }

    @Bean RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean Queue watcherIncomingQueue() { return durableQueue(INCOMING_QUEUE); }
    @Bean Queue watcherAdminQueue() { return durableQueue(ADMIN_QUEUE); }
    @Bean Queue watcherNotificationQueue() { return durableQueue(NOTIFICATION_QUEUE); }
    @Bean Queue watcherPromptResultQueue() { return durableQueue(PROMPT_RESULT_QUEUE); }
    @Bean Queue watcherDeadLetterQueue() { return QueueBuilder.durable(DEAD_LETTER_QUEUE).build(); }

    @Bean Queue incomingRetryQueueOne(RabbitMessagingProperties properties) {
        return retryQueue("watcher.incoming.retry.1", "incoming", 1_000, properties);
    }
    @Bean Queue incomingRetryQueueTwo(RabbitMessagingProperties properties) {
        return retryQueue("watcher.incoming.retry.2", "incoming", 5_000, properties);
    }
    @Bean Queue incomingRetryQueueThree(RabbitMessagingProperties properties) {
        return retryQueue("watcher.incoming.retry.3", "incoming", 30_000, properties);
    }
    @Bean Queue adminRetryQueueOne(RabbitMessagingProperties properties) {
        return retryQueue("watcher.admin.retry.1", "admin", 1_000, properties);
    }
    @Bean Queue adminRetryQueueTwo(RabbitMessagingProperties properties) {
        return retryQueue("watcher.admin.retry.2", "admin", 5_000, properties);
    }
    @Bean Queue adminRetryQueueThree(RabbitMessagingProperties properties) {
        return retryQueue("watcher.admin.retry.3", "admin", 30_000, properties);
    }
    @Bean Queue promptResultRetryQueueOne(RabbitMessagingProperties properties) {
        return retryQueue("watcher.prompt-results.retry.1", "prompt-results", 1_000, properties);
    }
    @Bean Queue promptResultRetryQueueTwo(RabbitMessagingProperties properties) {
        return retryQueue("watcher.prompt-results.retry.2", "prompt-results", 5_000, properties);
    }
    @Bean Queue promptResultRetryQueueThree(RabbitMessagingProperties properties) {
        return retryQueue("watcher.prompt-results.retry.3", "prompt-results", 30_000, properties);
    }
    @Bean Queue promptResultRateLimitQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.prompt-results.rate-limited", "prompt-results", properties);
    }
    @Bean Queue promptResultLeaseQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.prompt-results.lease", "prompt-results", 120_000, properties);
    }
    @Bean Queue notificationRetryQueueOne(RabbitMessagingProperties properties) {
        return retryQueue("watcher.notifications.retry.1", "notifications", 1_000, properties);
    }
    @Bean Queue notificationRetryQueueTwo(RabbitMessagingProperties properties) {
        return retryQueue("watcher.notifications.retry.2", "notifications", 5_000, properties);
    }
    @Bean Queue notificationRetryQueueThree(RabbitMessagingProperties properties) {
        return retryQueue("watcher.notifications.retry.3", "notifications", 30_000, properties);
    }
    @Bean Queue notificationRateLimitQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.notifications.rate-limited", "notifications", properties);
    }
    @Bean Queue notificationLeaseQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.notifications.lease", "notifications", 120_000, properties);
    }
    @Bean Queue adminRateLimitQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.admin.rate-limited", "admin", properties);
    }
    @Bean Queue adminLeaseQueue(RabbitMessagingProperties properties) {
        return retryQueue("watcher.admin.lease", "admin", 120_000, properties);
    }

    /** Optional staging topology; native ingress must not be enabled before producer recovery is validated. */
    @Bean
    @ConditionalOnProperty(prefix = "watcher.rabbitmq.native-evolution", name = "enabled", havingValue = "true")
    Declarables nativeEvolutionBindings(@Qualifier("watcherIncomingQueue") Queue queue,
            @org.springframework.beans.factory.annotation.Value("${watcher.rabbitmq.native-evolution.exchange:watcher.evolution}")
            String exchangeName) {
        if (exchangeName == null || exchangeName.isBlank())
            throw new IllegalArgumentException("native Evolution exchange is required");
        TopicExchange exchange = new TopicExchange(exchangeName, true, false);
        var declarations = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        declarations.add(exchange);
        for (String route : java.util.List.of("messages.upsert", "connection.update", "contacts.upsert",
                "contacts.update", "chats.upsert", "chats.update", "chats.set"))
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(route));
        return new Declarables(declarations);
    }

    @Bean Binding incomingBinding(@Qualifier("watcherIncomingQueue") Queue queue,
                                  @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("incoming");
    }

    @Bean Binding adminBinding(@Qualifier("watcherAdminQueue") Queue queue,
                               @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin");
    }

    @Bean Binding notificationBinding(@Qualifier("watcherNotificationQueue") Queue queue,
                                      @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications");
    }

    @Bean Binding promptResultBinding(@Qualifier("watcherPromptResultQueue") Queue queue,
                                      @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("prompt-results");
    }

    @Bean Binding promptResultRetryBindingOne(@Qualifier("promptResultRetryQueueOne") Queue queue,
                                              @Qualifier("watcherEventExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("prompt-results.retry.1");
    }
    @Bean Binding promptResultRetryBindingTwo(@Qualifier("promptResultRetryQueueTwo") Queue queue,
                                              @Qualifier("watcherEventExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("prompt-results.retry.2");
    }
    @Bean Binding promptResultRetryBindingThree(@Qualifier("promptResultRetryQueueThree") Queue queue,
                                                @Qualifier("watcherEventExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("prompt-results.retry.3");
    }
    @Bean Binding promptResultRateLimitBinding(@Qualifier("promptResultRateLimitQueue") Queue queue,
                                               @Qualifier("watcherEventExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("prompt-results.rate-limited");
    }
    @Bean Binding promptResultLeaseBinding(@Qualifier("promptResultLeaseQueue") Queue queue,
                                           @Qualifier("watcherEventExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("prompt-results.lease");
    }

    @Bean Binding deadLetterBinding(@Qualifier("watcherDeadLetterQueue") Queue queue,
                                    @Qualifier("watcherDeadLetterExchange") DirectExchange watcherDeadLetterExchange) {
        return BindingBuilder.bind(queue).to(watcherDeadLetterExchange).with("dead");
    }

    @Bean Binding incomingRetryBindingOne(@Qualifier("incomingRetryQueueOne") Queue queue,
                                          @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("incoming.retry.1");
    }
    @Bean Binding incomingRetryBindingTwo(@Qualifier("incomingRetryQueueTwo") Queue queue,
                                          @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("incoming.retry.2");
    }
    @Bean Binding incomingRetryBindingThree(@Qualifier("incomingRetryQueueThree") Queue queue,
                                            @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("incoming.retry.3");
    }

    @Bean Binding adminRetryBindingOne(@Qualifier("adminRetryQueueOne") Queue queue,
                                       @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin.retry.1");
    }
    @Bean Binding adminRetryBindingTwo(@Qualifier("adminRetryQueueTwo") Queue queue,
                                       @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin.retry.2");
    }
    @Bean Binding adminRetryBindingThree(@Qualifier("adminRetryQueueThree") Queue queue,
                                         @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin.retry.3");
    }
    @Bean Binding notificationRetryBindingOne(@Qualifier("notificationRetryQueueOne") Queue queue,
                                              @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications.retry.1");
    }
    @Bean Binding notificationRetryBindingTwo(@Qualifier("notificationRetryQueueTwo") Queue queue,
                                              @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications.retry.2");
    }
    @Bean Binding notificationRetryBindingThree(@Qualifier("notificationRetryQueueThree") Queue queue,
                                                @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications.retry.3");
    }
    @Bean Binding notificationRateLimitBinding(@Qualifier("notificationRateLimitQueue") Queue queue,
                                               @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications.rate-limited");
    }
    @Bean Binding notificationLeaseBinding(@Qualifier("notificationLeaseQueue") Queue queue,
                                           @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("notifications.lease");
    }
    @Bean Binding adminRateLimitBinding(@Qualifier("adminRateLimitQueue") Queue queue,
                                        @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin.rate-limited");
    }
    @Bean Binding adminLeaseBinding(@Qualifier("adminLeaseQueue") Queue queue,
                                    @Qualifier("watcherEventExchange") DirectExchange watcherEventExchange) {
        return BindingBuilder.bind(queue).to(watcherEventExchange).with("admin.lease");
    }

    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(20);
        factory.setAutoStartup(false);
        return factory;
    }

    private static Queue durableQueue(String name) {
        return QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey("dead").build();
    }

    private static Queue retryQueue(String name, String destinationRoutingKey, RabbitMessagingProperties properties) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(properties.getExchange())
                .deadLetterRoutingKey(destinationRoutingKey)
                .build();
    }

    private static Queue retryQueue(String name, String destinationRoutingKey, int ttl,
                                    RabbitMessagingProperties properties) {
        return QueueBuilder.durable(name)
                .withArgument("x-message-ttl", ttl)
                .deadLetterExchange(properties.getExchange())
                .deadLetterRoutingKey(destinationRoutingKey)
                .build();
    }
}

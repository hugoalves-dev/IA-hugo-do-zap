package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NativeEvolutionTopologyTest {
    @Test
    void nativeBindingsAreDisabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(RabbitMessagingConfiguration.class)
                .withPropertyValues("watcher.rabbitmq.enabled=true")
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .run(context -> assertThat(context).doesNotHaveBean("nativeEvolutionBindings"));
    }
}

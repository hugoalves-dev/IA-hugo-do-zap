package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitUnconfirmedDeliveryRecoveryTest {
    @Test
    void periodicallyRetriesOnlyUnconfirmedJobs() {
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        when(outbox.recoverUnconfirmedActions()).thenReturn(1);

        new RabbitUnconfirmedDeliveryRecovery(outbox).recover();

        verify(outbox).recoverUnconfirmedActions();
    }
}

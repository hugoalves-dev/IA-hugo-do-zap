package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RabbitDeliveryJobTest {
    @Test
    void roundTripsTheFullSensitivePayloadAndPendingSnapshot() {
        var expected = new RabbitDeliveryJob("archived_messages:chat:42", "archived_messages",
                "{\"chats\":[{\"messages\":[{\"text\":\"hello\"}]}]}",
                "[{\"chat_jid\":\"chat\",\"pending_generation\":\"42\"}]");

        var actual = RabbitDeliveryJob.parse(expected.toJson());

        assertEquals(expected, actual);
        assertTrue(actual.toJson().contains("hello"));
    }

    @Test
    void keepsTheLegacyActionKeyContractReadableDuringRollback() {
        assertNull(RabbitDeliveryJob.parse("telegram_admin_reply:42"));
    }

    @Test
    void rejectsMalformedNonLegacyBodies() {
        assertThrows(IllegalArgumentException.class, () -> RabbitDeliveryJob.parse("{broken"));
    }
}

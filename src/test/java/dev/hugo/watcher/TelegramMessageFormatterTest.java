package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageFormatterTest {
    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test void formatsArchivedMessagesAsAUsefulPrivateSummary() {
        String text = formatter.format(new OutboxAction(1, "archive", "archived_messages", """
                {"eventType":"archived_messages","totalChats":2,"totalMessages":5,"chats":[
                  {"displayName":"Ana","messageCount":2,"lastMessageAt":"10:00","lastMessagePreview":"Oi, tudo bem?"},
                  {"displayName":"Equipe","messageCount":3,"lastMessageAt":"10:30","lastMessagePreview":"[Image]"}]}
                """, "[]", 1, null, null, null));

        assertTrue(text.contains("Mensagens arquivadas"));
        assertTrue(text.contains("2 conversas"));
        assertTrue(text.contains("5 mensagens"));
        assertTrue(text.contains("Ana"));
        assertTrue(text.contains("Oi, tudo bem?"));
    }

    @Test void formatsConnectionLossWithManualPairingInstructions() {
        String text = formatter.format(new OutboxAction(2, "connection", "whatsapp_connection_lost", """
                {"eventType":"whatsapp_connection_lost","reason":"device_removed","manualPairingRequired":true}
                """, "[]", 1, null, null, null));

        assertTrue(text.contains("Conexão do WhatsApp perdida"));
        assertTrue(text.contains("pareamento manual"));
    }

    @Test void boundsTelegramTextAndDoesNotExposeTheBotToken() {
        String hugePreview = "x".repeat(5000);
        String text = formatter.format(new OutboxAction(3, "archive", "archived_messages",
                "{\"eventType\":\"archived_messages\",\"totalChats\":1,\"totalMessages\":1,\"chats\":[{\"displayName\":\"Contato\",\"messageCount\":1,\"lastMessagePreview\":\"" + hugePreview + "\"}]}",
                "[]", 1, null, null, null));

        assertTrue(text.length() <= TelegramMessageFormatter.MAX_MESSAGE_LENGTH);
        assertFalse(text.contains("bot-token-secret"));
    }
}

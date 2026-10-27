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

    @Test void formatsDailySimulationReportWithoutConversationContent() {
        String text = formatter.format(new OutboxAction(5, "daily", "simulation_daily_summary", """
                {"eventType":"simulation_daily_summary","date":"2026-10-27","decisionCount":4,
                 "predictedAlerts":1,"predictedReplies":2,"blockedDecisions":1,
                 "reservedCostMicros":50,"reportedActualCostMicros":25}
                """, "[]", 1, null, null, null));

        assertTrue(text.contains("Resumo diário de simulação"));
        assertTrue(text.contains("Decisões: 4"));
        assertTrue(text.contains("Custo reservado: 50 micros"));
        assertTrue(text.contains("Sem conteúdo de conversas"));
        assertFalse(text.contains("message"));
    }

    @Test void boundsTelegramTextAndDoesNotExposeTheBotToken() {
        String hugePreview = "x".repeat(5000);
        String text = formatter.format(new OutboxAction(3, "archive", "archived_messages",
                "{\"eventType\":\"archived_messages\",\"totalChats\":1,\"totalMessages\":1,\"chats\":[{\"displayName\":\"Contato\",\"messageCount\":1,\"lastMessagePreview\":\"" + hugePreview + "\"}]}",
                "[]", 1, null, null, null));

        assertTrue(text.length() <= TelegramMessageFormatter.MAX_MESSAGE_LENGTH);
        assertFalse(text.contains("bot-token-secret"));
    }

    @Test void truncationKeepsBothHalvesWhenBoundaryEndsOnLowSurrogate() {
        String prefix = formatter.format(archivedWithName("x"));
        int nameStart = prefix.indexOf("x — 1 mensagem");
        int highIndex = TelegramMessageFormatter.MAX_MESSAGE_LENGTH - 3;
        String name = "a".repeat(highIndex - nameStart) + "😀" + "z".repeat(100);

        String text = formatter.format(archivedWithName(name));

        assertTrue(text.contains("😀"));
        assertWellFormedUtf16(text);
    }

    @Test void truncationDropsWholePairWhenBoundaryWouldEndOnHighSurrogate() {
        String prefix = formatter.format(archivedWithName("x"));
        int nameStart = prefix.indexOf("x — 1 mensagem");
        int highIndex = TelegramMessageFormatter.MAX_MESSAGE_LENGTH - 2;
        String name = "a".repeat(highIndex - nameStart) + "😀" + "z".repeat(100);

        String text = formatter.format(archivedWithName(name));

        assertFalse(text.contains("😀"));
        assertWellFormedUtf16(text);
    }

    private OutboxAction archivedWithName(String name) {
        String safeName = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return new OutboxAction(4, "unicode", "archived_messages",
                "{\"eventType\":\"archived_messages\",\"totalChats\":1,\"totalMessages\":1,\"chats\":[{\"displayName\":\"" + safeName + "\",\"messageCount\":1}]}",
                "[]", 1, null, null, null);
    }

    private static void assertWellFormedUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)));
            } else assertFalse(Character.isLowSurrogate(current));
        }
    }
}

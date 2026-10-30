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

    @Test void usesModeLocalizedDateEscapedTextAndValidatedPhoneLink() {
        String text = formatter.format(new OutboxAction(8, "archive", "archived_messages", """
                {"eventType":"archived_messages","archiveMode":"ALL","totalChats":1,"totalMessages":1,"chats":[
                 {"displayName":"Maria <Silva> & Cia","phoneJid":"5514998496595@s.whatsapp.net","isGroup":false,
                  "messageCount":1,"lastMessageAt":"2026-10-29T19:53:14Z","lastMessagePreview":"<b>oi & tchau</b>"}]}
                """, "[]", 1, null, null, null));
        assertTrue(text.contains("📬 Novas mensagens"));
        assertTrue(text.contains("1 conversa · 1 mensagem"));
        assertTrue(text.contains("<a href=\"https://wa.me/5514998496595\">Maria &lt;Silva&gt; &amp; Cia</a>"));
        assertTrue(text.contains("29/10/2026 - 16:53:14"));
        assertTrue(text.contains("&lt;b&gt;oi &amp; tchau&lt;/b&gt;"));
    }

    @Test void unarchivedModeNeverLinksLidsGroupsOrInvalidNumbersAndOmitsInvalidDates() {
        String text = formatter.format(new OutboxAction(9, "archive", "archived_messages", """
                {"eventType":"archived_messages","archiveMode":"UNARCHIVED_ONLY","totalChats":3,"totalMessages":3,"chats":[
                 {"displayName":"Conversa","phoneJid":"123456789@lid","messageCount":1,"lastMessageAt":"not-a-date"},
                 {"displayName":"Grupo","phoneJid":"5511999999999@s.whatsapp.net","isGroup":true,"messageCount":1},
                 {"displayName":"Contato","phoneJid":"123@s.whatsapp.net","messageCount":1}]}
                """, "[]", 1, null, null, null));
        assertTrue(text.contains("📬 Mensagens não arquivadas"));
        assertFalse(text.contains("<a "));
        assertFalse(text.contains("not-a-date"));
    }

    @Test void convertsOffsetTimestampIntoConfiguredTimezoneOnlyOnce() {
        WatcherProperties properties = new WatcherProperties(); properties.setTimezone("Europe/Lisbon");
        String text = new TelegramMessageFormatter(properties).format(new OutboxAction(10, "archive", "archived_messages", """
                {"eventType":"archived_messages","archiveMode":"ALL","totalChats":1,"totalMessages":1,"chats":[
                 {"displayName":"Ana","messageCount":1,"lastMessageAt":"2026-10-29T16:53:14-03:00"}]}
                """, "[]", 1, null, null, null));
        assertTrue(text.contains("29/10/2026 - 19:53:14"));
    }

    @Test void truncationKeepsHtmlEntitiesAndLinksWellFormed() throws Exception {
        String name = "<&\"😀".repeat(500);
        var json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
                "eventType", "archived_messages", "archiveMode", "ALL", "totalChats", 2, "totalMessages", 2,
                "chats", java.util.List.of(java.util.Map.of("displayName",name,"messageCount",1,
                "phoneJid","5511999999999@s.whatsapp.net","lastMessagePreview",name),
                java.util.Map.of("displayName",name,"messageCount",1,"phoneJid","5511888888888@s.whatsapp.net"))));
        String text = formatter.format(new OutboxAction(11, "archive", "archived_messages", json, "[]", 1, null, null, null));
        assertTrue(text.length() <= TelegramMessageFormatter.MAX_MESSAGE_LENGTH);
        assertWellFormedUtf16(text);
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
        document.parse(new org.xml.sax.InputSource(new java.io.StringReader("<root>" + text + "</root>")));
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

    @Test void formatsIntelligentAlertWithOnlyUrgencyAndDecisionReference() {
        String reference = "54a5e6ad-7910-4dd6-a35b-8f14e5aee217";
        String text = formatter.format(new OutboxAction(6, "alert", "intelligent_alert", """
                {"eventType":"intelligent_alert","decisionId":"54a5e6ad-7910-4dd6-a35b-8f14e5aee217",
                 "urgency":"CRITICAL","reference":"54a5e6ad-7910-4dd6-a35b-8f14e5aee217"}
                """, "[]", 1, null, null, null));

        assertTrue(text.contains("urgência CRITICAL"));
        assertTrue(text.contains(reference));
        assertTrue(text.contains("Sem conteúdo de conversa"));
        assertFalse(text.contains("RAW"));
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
        String name = "a".repeat(237) + "😀" + "z".repeat(100);

        String text = formatter.format(archivedWithName(name));

        assertTrue(text.contains("😀"));
        assertWellFormedUtf16(text);
    }

    @Test void truncationDropsWholePairWhenBoundaryWouldEndOnHighSurrogate() {
        String name = "a".repeat(238) + "😀" + "z".repeat(100);

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
    @org.junit.jupiter.api.Test void adminReplyRetainsReadableLineBreaks() {
        var now = java.time.Instant.parse("2026-10-27T12:00:00Z");
        var action = new OutboxAction(1, "test", "telegram_admin_reply", "{\"eventType\":\"telegram_admin_reply\",\"text\":\"linha um\\nlinha dois\"}", "[]", 0, now, now.plusSeconds(60), null);
        org.junit.jupiter.api.Assertions.assertEquals("linha um\nlinha dois", new TelegramMessageFormatter().format(action));
    }
}

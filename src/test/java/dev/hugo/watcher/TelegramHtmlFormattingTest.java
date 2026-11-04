package dev.hugo.watcher;

import com.pengrad.telegrambot.model.request.ParseMode;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramHtmlFormattingTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    private OutboxAction action(String type, String json) {
        return new OutboxAction(1, "html-test", type, json, "[]", 0,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), null);
    }

    private static void assertValidHtml(String html) throws Exception {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader("<root>" + html + "</root>")));
    }

    @Test void archivedNotificationsUseBoldHeadingsAndMultilineBlockquotesWithoutMarkupInjection() throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "eventType", "archived_messages", "archiveMode", "ALL",
                "totalChats", 2, "totalMessages", 3,
                "chats", java.util.List.of(
                        Map.of("displayName", "Ana <B> & \"Cia\" 'x'",
                                "phoneJid", "5511999999999@s.whatsapp.net", "isGroup", false,
                                "messageCount", 2, "lastMessageAt", "2026-11-03T17:00:20Z",
                                "lastMessagePreview", "1ª linha <script>\n2ª & \"linha\" 'teste'"),
                        Map.of("displayName", "Grupo & <dev>", "phoneJid", "5511888888888@s.whatsapp.net",
                                "isGroup", true, "messageCount", 1, "lastMessagePreview", "😀 agora"))));
        String html = formatter.format(action("archived_messages", payload));

        assertTrue(html.startsWith("<b>📬 Novas mensagens</b>\n2 conversas · 3 mensagens"));
        assertTrue(html.contains("<a href=\"https://wa.me/5511999999999\">Ana &lt;B&gt; &amp; &quot;Cia&quot; &#39;x&#39;</a>"));
        assertTrue(html.contains("<b>• Grupo &amp; &lt;dev&gt; — 1 mensagem</b>"));
        assertEquals(1, html.split("<a href=", -1).length - 1);
        assertTrue(html.contains("<blockquote>1ª linha &lt;script&gt;\n2ª &amp; &quot;linha&quot; &#39;teste&#39;</blockquote>"));
        assertTrue(html.contains("<blockquote>😀 agora</blockquote>"));
        assertTrue(html.contains("03/11/2026 - 14:00:20"));
        assertValidHtml(html);
    }

    @Test void digestsAndAlertsUseHtmlTitlesAndEscapeDynamicContent() throws Exception {
        String digest = formatter.format(action("simulation_daily_summary", """
                {"eventType":"simulation_daily_summary","date":"<img src='x'>&","decisionCount":48}
                """));
        assertTrue(digest.startsWith("<b>📊 Resumo diário de simulação</b> — &lt;img src=&#39;x&#39;&gt;&amp;"));
        assertValidHtml(digest);
        String alert = formatter.format(action("intelligent_alert", """
                {"eventType":"intelligent_alert","urgency":"CRITICAL",
                 "reference":"54a5e6ad-7910-4dd6-a35b-8f14e5aee217"}
                """));
        assertTrue(alert.startsWith("<b>⚠️ Alerta inteligente</b> — urgência CRITICAL"));
        assertValidHtml(alert);
        String lowAlert = formatter.format(action("intelligent_alert", """
                {"eventType":"intelligent_alert","urgency":"LOW",
                 "reference":"54a5e6ad-7910-4dd6-a35b-8f14e5aee217"}
                """));
        assertTrue(lowAlert.startsWith("<b>⚠️ Alerta inteligente</b> — urgência LOW"));
        assertValidHtml(lowAlert);
    }

    @Test void dailySummaryShowsSemanticRecommendationsSeparatelyFromActions() throws Exception {
        String html = formatter.format(action("simulation_daily_summary", """
                {"eventType":"simulation_daily_summary","date":"2026-11-03",
                 "semanticTriage":{"IGNORE":2,"NOTIFY_ONLY":3,"NOTIFY_AND_REPLY":4,"UNDECIDED":1},
                 "recommendations":{"notifications":7,"replies":4},
                 "simulatedActions":{"alerts":0,"replies":0},"realAuthorizedActions":0}
                """));

        assertTrue(html.contains("Triagem IGNORE/NOTIFY_ONLY/NOTIFY_AND_REPLY/UNDECIDED: 2/3/4/1"));
        assertTrue(html.contains("recomendações alerta/resposta: 7/4"));
        assertTrue(html.contains("autorizadas em modo real: 0"));
        assertValidHtml(html);
    }

    @Test void simulatedReplyRendersOnlyProvidedTemplateAndNeverClaimsWhatsAppWasSent() throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "eventType", "simulation_reply_preview", "decisionId", "54a5e6ad-7910-4dd6-a35b-8f14e5aee217",
                "displayName", "Maria <&>", "urgency", "HIGH", "timestamp", "2026-11-03T17:00:20Z",
                "reason", "Contato solicitou retorno", "templatePreview", "Olá <Maria>\nResposta & orientação 😀"));
        String html = formatter.format(action("simulation_reply_preview", payload));
        assertTrue(html.contains("<b>🧪 SIMULAÇÃO — não enviada no WhatsApp</b>"));
        assertTrue(html.contains("Contato: Maria &lt;&amp;&gt;"));
        assertTrue(html.contains("Decisão: 54a5e6ad-7910-4dd6-a35b-8f14e5aee217"));
        assertTrue(html.contains("<blockquote>Olá &lt;Maria&gt;\nResposta &amp; orientação 😀</blockquote>"));
        assertValidHtml(html);
    }

    @Test void simulatedReplyWithNoApprovedTemplateNeverFabricatesOne() throws Exception {
        String html = formatter.format(action("simulation_reply_preview", """
                {"eventType":"simulation_reply_preview","decisionId":"54a5e6ad-7910-4dd6-a35b-8f14e5aee217"}
                """));
        assertTrue(html.contains("template aprovado ausente"));
        assertFalse(html.contains("<blockquote>"));
        assertValidHtml(html);
    }

    @Test void longEscapedSimulationPreviewRespectsLengthAndDoesNotBreakSurrogatePairsOrTags() throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "eventType", "simulation_reply_preview", "decisionId", "54a5e6ad-7910-4dd6-a35b-8f14e5aee217",
                "templatePreview", "<&😀'\">".repeat(5000)));
        String html = formatter.format(action("simulation_reply_preview", payload));
        assertTrue(html.length() <= TelegramMessageFormatter.MAX_MESSAGE_LENGTH);
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.endsWith("</blockquote>"));
        for (int i = 0; i < html.length(); i++) {
            if (Character.isHighSurrogate(html.charAt(i))) {
                assertTrue(i + 1 < html.length() && Character.isLowSurrogate(html.charAt(++i)));
            } else assertFalse(Character.isLowSurrogate(html.charAt(i)));
        }
        assertValidHtml(html);
    }

    @Test void menuUsesHtmlButOtherAdminRepliesStayPlainText() throws Exception {
        OutboxAction menu = action("telegram_admin_edit", """
                {"eventType":"telegram_admin_edit",
                 "text":"⚙️ Administração Hugo do Zap\\nEscolha <uma> & opção:",
                 "messageId":10,"keyboard":[[{"text":"Status","data":"status"}]]}
                """);
        assertTrue(formatter.isHtmlAdmin(menu));
        String html = formatter.formatAdminHtml(menu);
        assertTrue(html.startsWith("[🤖 HUGO ZAP BOT]: <b>⚙️ Administração Hugo do Zap</b>\n"));
        assertTrue(html.contains("Escolha &lt;uma&gt; &amp; opção:"));
        assertEquals(1, html.split("\\[🤖 HUGO ZAP BOT\\]: ", -1).length - 1);
        assertValidHtml(html);
        OutboxAction plain = action("telegram_admin_reply",
                "{\"eventType\":\"telegram_admin_reply\",\"text\":\"<b>literal</b>\"}");
        assertFalse(formatter.isHtmlAdmin(plain));
        assertEquals("[🤖 HUGO ZAP BOT]: <b>literal</b>", formatter.format(plain));
    }

    @Test void telegramRequestsEnableHtmlOnlyOnExplicitFormattedPaths() {
        var rich = TelegramBotApiTransport.message(99L, "<b>ok</b>", true);
        var plain = TelegramBotApiTransport.message(99L, "<b>literal</b>", false);
        var editedRich = TelegramBotApiTransport.editMessage(99L, 42, "<b>ok</b>", true);
        var editedPlain = TelegramBotApiTransport.editMessage(99L, 42, "<b>literal</b>", false);
        assertEquals(ParseMode.HTML, rich.getParameters().get("parse_mode"));
        assertEquals("HTML", editedRich.getParameters().get("parse_mode"));
        assertFalse(plain.getParameters().containsKey("parse_mode"));
        assertFalse(editedPlain.getParameters().containsKey("parse_mode"));
    }
}

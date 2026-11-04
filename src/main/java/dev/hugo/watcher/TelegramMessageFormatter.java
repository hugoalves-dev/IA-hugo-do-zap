package dev.hugo.watcher;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;

/** Converts durable outbox payloads into short, readable Telegram messages. */
@Component
public class TelegramMessageFormatter {
    static final int MAX_MESSAGE_LENGTH = 3500;
    private static final int MAX_PREVIEW_LENGTH = 180;
    private static final int MAX_NAME_LENGTH = 240;
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu - HH:mm:ss");
    private final ZoneId timezone;
    @Autowired public TelegramMessageFormatter(WatcherProperties properties) { this.timezone = ZoneId.of(properties.getTimezone()); }
    public TelegramMessageFormatter() { this(new WatcherProperties()); }
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String format(OutboxAction action) {
        try {
            JsonNode payload = JSON.readTree(action.payload());
            return switch (payload.path("eventType").asString("")) {
                case "telegram_admin_reply", "telegram_admin_edit" -> limit(BotReplyFormatter.telegramAdmin(cleanAdmin(payload.path("text").asString(""))), MAX_MESSAGE_LENGTH);
                case NotificationService.ARCHIVED_MESSAGES -> archived(payload).text();
                case ConnectionService.ACTION_TYPE -> connection(payload);
                case "simulation_daily_summary" -> dailySummary(payload);
                case "intelligent_alert" -> intelligentAlert(payload);
                case "simulation_reply_preview" -> simulationReplyPreview(payload);
                default -> "📬 O watcher registrou uma nova atualização do WhatsApp.";
            };
        } catch (RuntimeException ex) {
            if (action != null && ("telegram_admin_reply".equals(action.actionType())
                    || "telegram_admin_edit".equals(action.actionType())))
                return BotReplyFormatter.telegramAdmin("Não foi possível formatar a resposta administrativa.");
            return "⚠️ O watcher registrou uma atualização do WhatsApp, mas não conseguiu formatar os detalhes.";
        }
    }

    /** Only the structured /menu reply needs rich admin formatting; all other commands stay literal text. */
    boolean isHtmlAdmin(OutboxAction action) {
        if (action == null || !List.of("telegram_admin_reply", "telegram_admin_edit").contains(action.actionType()))
            return false;
        try {
            return JSON.readTree(action.payload()).path("text").asString("")
                    .startsWith("⚙️ Administração Hugo do Zap\n");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    String formatAdminHtml(OutboxAction action) {
        String plain = format(action);
        String title = BotReplyFormatter.PREFIX + "⚙️ Administração Hugo do Zap";
        if (!plain.startsWith(title)) return escape(plain);
        return escape(BotReplyFormatter.PREFIX) + "<b>⚙️ Administração Hugo do Zap</b>"
                + escape(plain.substring(title.length()));
    }

    int includedArchivedChats(String payload) {
        return archived(JSON.readTree(payload)).includedChats();
    }

    private ArchivedRendering archived(JsonNode payload) {
        String title = switch (payload.path("archiveMode").asString("ARCHIVED_ONLY")) {
            case "ALL" -> "📬 Novas mensagens";
            case "UNARCHIVED_ONLY" -> "📬 Mensagens não arquivadas";
            default -> "📬 Mensagens arquivadas";
        };
        int totalChats = payload.path("totalChats").asInt(0);
        int totalMessages = payload.path("totalMessages").asInt(0);
        StringBuilder text = new StringBuilder("<b>").append(title).append("</b>\n").append(totalChats)
                .append(totalChats == 1 ? " conversa · " : " conversas · ").append(totalMessages)
                .append(totalMessages == 1 ? " mensagem" : " mensagens");
        int includedChats = 0;
        JsonNode chats = payload.path("chats");
        if (chats.isArray()) for (JsonNode chat : chats) {
            String name = escape(limit(clean(chat.path("displayName").asString("Conversa")), MAX_NAME_LENGTH));
            if (name.isBlank()) name = "Conversa";
            String phoneJid = chat.path("phoneJid").asString("");
            String phone = phoneJid.matches("(?i)^[1-9][0-9]{7,14}(?::[0-9]+)?@s\\.whatsapp\\.net$")
                    ? phoneJid.substring(0, phoneJid.indexOf('@')).split(":", 2)[0] : null;
            if (!chat.path("isGroup").asBoolean(false) && phone != null)
                name = "<a href=\"https://wa.me/" + phone + "\">" + name + "</a>";
            int count = chat.path("messageCount").asInt(0);
            StringBuilder line = new StringBuilder("\n\n<b>• ").append(name).append(" — ").append(count)
                    .append(count == 1 ? " mensagem</b>" : " mensagens</b>");
            try {
                Instant last = Instant.parse(chat.path("lastMessageAt").asString(""));
                line.append("\nÚltima: ").append(LOCAL_DATE.withZone(timezone).format(last));
            } catch (RuntimeException ignored) { }
            String preview = cleanPreview(chat.path("lastMessagePreview").asString(""));
            if (!preview.isEmpty()) line.append("\n<blockquote>")
                    .append(escape(limit(preview, MAX_PREVIEW_LENGTH))).append("</blockquote>");
            if (text.length() + line.length() > MAX_MESSAGE_LENGTH) {
                if (text.length() < MAX_MESSAGE_LENGTH) text.append("…");
                break;
            }
            text.append(line);
            includedChats++;
        }
        return new ArchivedRendering(text.toString(), includedChats);
    }

    private record ArchivedRendering(String text, int includedChats) { }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String connection(JsonNode payload) {
        if (payload.path("manualPairingRequired").asBoolean(false)) {
            return "⚠️ Conexão do WhatsApp perdida\nO dispositivo foi removido. Faça o pareamento manual do WhatsApp para retomar as notificações.";
        }
        String reason = clean(payload.path("reason").asString("connection_lost"));
        return "⚠️ Conexão do WhatsApp perdida\nMotivo informado: " + limit(reason, 120)
                + ". Verifique o WhatsApp no telefone; as notificações serão retomadas quando a conexão voltar.";
    }

    private String dailySummary(JsonNode payload) {
        return "<b>📊 Resumo diário de simulação</b> — " + escape(limit(clean(payload.path("date").asString("")), 80))
                + "\nDecisões: " + payload.path("decisionCount").asLong(0)
                + " · alertas previstos: " + payload.path("predictedAlerts").asLong(0)
                + " · respostas previstas: " + payload.path("predictedReplies").asLong(0)
                + " · bloqueadas: " + payload.path("blockedDecisions").asLong(0)
                + "\nTriagem IGNORE/NOTIFY_ONLY/NOTIFY_AND_REPLY/UNDECIDED: "
                + payload.path("semanticTriage").path("IGNORE").asLong(0) + "/"
                + payload.path("semanticTriage").path("NOTIFY_ONLY").asLong(0) + "/"
                + payload.path("semanticTriage").path("NOTIFY_AND_REPLY").asLong(0) + "/"
                + payload.path("semanticTriage").path("UNDECIDED").asLong(0)
                + " · recomendações alerta/resposta: "
                + payload.path("recommendations").path("notifications").asLong(0) + "/"
                + payload.path("recommendations").path("replies").asLong(0)
                + " · autorizadas em modo real: " + payload.path("realAuthorizedActions").asLong(0)
                + "\nUrgência LOW/NORMAL/HIGH/CRITICAL/UNCERTAIN: "
                + payload.path("urgencyDistribution").path("LOW").asLong(0) + "/"
                + payload.path("urgencyDistribution").path("NORMAL").asLong(0) + "/"
                + payload.path("urgencyDistribution").path("HIGH").asLong(0) + "/"
                + payload.path("urgencyDistribution").path("CRITICAL").asLong(0) + "/"
                + payload.path("urgencyDistribution").path("UNCERTAIN").asLong(0)
                + " · classificações incertas: " + payload.path("uncertainCount").asLong(0)
                + "/" + payload.path("decisionCount").asLong(0)
                + " · indisponíveis: " + payload.path("unavailableCount").asLong(0)
                + "\nCusto reservado: " + payload.path("reservedCostMicros").asLong(0)
                + " micros · informado: " + payload.path("reportedActualCostMicros").asLong(0)
                + " micros (ledger local; custo externo pode diferir). Sem conteúdo de conversas.";
    }

    private String intelligentAlert(JsonNode payload) {
        String urgency = clean(payload.path("urgency").asString(""));
        String reference = clean(payload.path("reference").asString(""));
        if (!List.of("LOW", "NORMAL", "HIGH", "CRITICAL").contains(urgency) || !reference.matches("[0-9a-fA-F-]{36}"))
            return "⚠️ O watcher bloqueou um alerta sem classificação ou referência válida.";
        return "<b>⚠️ Alerta inteligente</b> — urgência " + escape(urgency) + "\nReferência da decisão: " + escape(reference)
                + ". Sem conteúdo de conversa.";
    }

    /**
     * Presentation-only consumer of a future #31 outbox event; it never generates or sends a reply.
     * The producer must authorize and supply the exact approved template body.
     */
    private String simulationReplyPreview(JsonNode payload) {
        String decisionId = clean(payload.path("decisionId").asString(""));
        if (!decisionId.matches("[0-9a-fA-F-]{36}"))
            return "<b>⚠️ Prévia de simulação indisponível</b>\nReferência inválida.";
        String name = escape(limit(clean(payload.path("displayName").asString("Conversa")), MAX_NAME_LENGTH));
        String urgency = escape(limit(clean(payload.path("urgency").asString("UNCERTAIN")), 32));
        StringBuilder text = new StringBuilder("<b>🧪 SIMULAÇÃO — não enviada no WhatsApp</b>")
                .append("\nContato: ").append(name.isBlank() ? "Conversa" : name)
                .append("\nUrgência: ").append(urgency)
                .append("\nDecisão: ").append(escape(decisionId));
        try {
            Instant at = Instant.parse(payload.path("timestamp").asString(""));
            text.append("\nHorário: ").append(LOCAL_DATE.withZone(timezone).format(at));
        } catch (RuntimeException ignored) { }
        String reason = clean(payload.path("reason").asString(""));
        if (!reason.isEmpty()) text.append("\nMotivo: ").append(escape(limit(reason, 200)));
        String template = cleanPreview(payload.path("templatePreview").asString(""));
        if (!template.isBlank()) {
            String opening = "\n<b>Prévia do template aprovado:</b>\n<blockquote>";
            String closing = "</blockquote>";
            int budget = MAX_MESSAGE_LENGTH - text.length() - opening.length() - closing.length();
            if (budget > 0) text.append(opening).append(escapeWithin(template, budget)).append(closing);
        } else {
            text.append("\nResposta recomendada, mas template aprovado ausente.");
        }
        return text.toString();
    }

    /** Escape per code point and reserve space for an ellipsis without splitting a surrogate pair. */
    private static String escapeWithin(String value, int budget) {
        StringBuilder escaped = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int next = offset + Character.charCount(codePoint);
            String part = escape(new String(Character.toChars(codePoint)));
            if (escaped.length() + part.length() + (next < value.length() ? 1 : 0) > budget) {
                if (escaped.length() < budget) escaped.append("…");
                break;
            }
            escaped.append(part);
            offset = next;
        }
        return escaped.toString();
    }

    private static String cleanPreview(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
    }

    private static String cleanAdmin(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").strip();
    }

    private static String limit(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = maxLength - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, Math.max(0, end)) + "…";
    }
}

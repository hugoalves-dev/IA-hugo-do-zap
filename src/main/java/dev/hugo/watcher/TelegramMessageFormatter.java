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
                case "telegram_admin_reply" -> limit(clean(payload.path("text").asString("")), MAX_MESSAGE_LENGTH);
                case NotificationService.ARCHIVED_MESSAGES -> archived(payload).text();
                case ConnectionService.ACTION_TYPE -> connection(payload);
                case "simulation_daily_summary" -> dailySummary(payload);
                case "intelligent_alert" -> intelligentAlert(payload);
                default -> "📬 O watcher registrou uma nova atualização do WhatsApp.";
            };
        } catch (RuntimeException ex) {
            return "⚠️ O watcher registrou uma atualização do WhatsApp, mas não conseguiu formatar os detalhes.";
        }
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
        StringBuilder text = new StringBuilder(title).append("\n").append(totalChats)
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
            StringBuilder line = new StringBuilder("\n• ").append(name).append(" — ").append(count)
                    .append(count == 1 ? " mensagem" : " mensagens");
            try {
                Instant last = Instant.parse(chat.path("lastMessageAt").asString(""));
                line.append("\n  Última: ").append(LOCAL_DATE.withZone(timezone).format(last));
            } catch (RuntimeException ignored) { }
            String preview = clean(chat.path("lastMessagePreview").asString(""));
            if (!preview.isEmpty()) line.append("\n  ").append(escape(limit(preview, MAX_PREVIEW_LENGTH)));
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
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
        return "📊 Resumo diário de simulação — " + clean(payload.path("date").asString(""))
                + "\nDecisões: " + payload.path("decisionCount").asLong(0)
                + " · alertas previstos: " + payload.path("predictedAlerts").asLong(0)
                + " · respostas previstas: " + payload.path("predictedReplies").asLong(0)
                + " · bloqueadas: " + payload.path("blockedDecisions").asLong(0)
                + "\nCusto reservado: " + payload.path("reservedCostMicros").asLong(0)
                + " micros · informado: " + payload.path("reportedActualCostMicros").asLong(0)
                + " micros. Sem conteúdo de conversas.";
    }

    private String intelligentAlert(JsonNode payload) {
        String urgency = clean(payload.path("urgency").asString(""));
        String reference = clean(payload.path("reference").asString(""));
        if (!List.of("HIGH", "CRITICAL").contains(urgency) || !reference.matches("[0-9a-fA-F-]{36}"))
            return "⚠️ O watcher bloqueou um alerta sem classificação ou referência válida.";
        return "⚠️ Alerta inteligente — urgência " + urgency + "\nReferência da decisão: " + reference
                + ". Sem conteúdo de conversa.";
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

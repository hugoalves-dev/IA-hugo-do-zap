package dev.hugo.watcher;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

/** Converts durable outbox payloads into short, readable Telegram messages. */
@Component
public class TelegramMessageFormatter {
    static final int MAX_MESSAGE_LENGTH = 3500;
    private static final int MAX_PREVIEW_LENGTH = 180;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String format(OutboxAction action) {
        try {
            JsonNode payload = JSON.readTree(action.payload());
            return switch (payload.path("eventType").asString("")) {
                case "telegram_admin_reply" -> limit(clean(payload.path("text").asString("")), MAX_MESSAGE_LENGTH);
                case NotificationService.ARCHIVED_MESSAGES -> archived(payload);
                case ConnectionService.ACTION_TYPE -> connection(payload);
                case "simulation_daily_summary" -> dailySummary(payload);
                case "intelligent_alert" -> intelligentAlert(payload);
                default -> "📬 O watcher registrou uma nova atualização do WhatsApp.";
            };
        } catch (RuntimeException ex) {
            return "⚠️ O watcher registrou uma atualização do WhatsApp, mas não conseguiu formatar os detalhes.";
        }
    }

    private String archived(JsonNode payload) {
        StringBuilder text = new StringBuilder("📬 Mensagens arquivadas\n")
                .append(payload.path("totalChats").asInt(0)).append(" conversas · ")
                .append(payload.path("totalMessages").asInt(0)).append(" mensagens");
        JsonNode chats = payload.path("chats");
        if (chats.isArray()) {
            for (JsonNode chat : chats) {
                String name = clean(chat.path("displayName").asString("Conversa"));
                int count = chat.path("messageCount").asInt(0);
                String last = clean(chat.path("lastMessageAt").asString(""));
                String preview = clean(chat.path("lastMessagePreview").asString(""));
                text.append("\n• ").append(name).append(" — ").append(count)
                        .append(count == 1 ? " mensagem" : " mensagens");
                if (!last.isEmpty()) text.append(" · última: ").append(last);
                if (!preview.isEmpty()) text.append("\n  ").append(limit(preview, MAX_PREVIEW_LENGTH));
                if (text.length() >= MAX_MESSAGE_LENGTH) break;
            }
        }
        return limit(text.toString(), MAX_MESSAGE_LENGTH);
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

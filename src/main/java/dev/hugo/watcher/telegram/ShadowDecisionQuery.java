package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Read-only inspection of retained decisions; never re-evaluates a batch. */
final class ShadowDecisionQuery {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo"));
    private final JdbcTemplate jdbc;
    private final Clock clock;
    ShadowDecisionQuery(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    String recent(List<String> args) {
        if (args.size() > 1 || !args.isEmpty() && !args.getFirst().matches("(?:[1-9]|10)"))
            throw new IllegalArgumentException("Use /ultimas [1-10].");
        int limit = args.isEmpty() ? 5 : Integer.parseInt(args.getFirst());
        List<String> rows = jdbc.query("""
                select d.batch_id,d.decided_at,d.classification_status,d.classification_urgency,d.classification_confidence,
                       d.would_alert,d.would_reply,d.blocked_reason,d.decision_state,d.classification_diagnostic,c.display_name,c.display_name_source,
                       s.display_name as snapshot_name,e.result->>'provider' as provider,
                       e.result->>'requestDetected' as request_detected
                  from simulation_decisions d join simulation_batches b on b.batch_id=d.batch_id
                  left join contact_identity c on c.canonical_jid=b.chat_jid
                  left join chat_snapshot s on s.chat_jid=b.chat_jid and s.synchronized_snapshot=true
                  left join simulation_ai_evaluations e on e.batch_id=d.batch_id
                 where not d.production_mode and d.metadata_expires_at>? order by d.decided_at desc,d.batch_id limit ?
                """, (rs, n) -> {
            String name = rs.getString("display_name");
            String source = rs.getString("display_name_source");
            if (source == null || !List.of("CONTACT_NAME", "NOTIFY", "VERIFIED", "CHAT", "LEGACY", "USERNAME").contains(source)
                    || !safeName(name)) name = rs.getString("snapshot_name");
            if (!safeName(name)) name = "Contato indisponível";
            name = name.strip().replaceAll("[\\p{Cntrl}]", " ");
            if (name.length() > 30) name = name.substring(0,30);
            name = escape(name);
            return rs.getString("batch_id").substring(0,8) + " · " + DATE.format(rs.getTimestamp("decided_at").toInstant())
                    + " · " + name + "\n" + rs.getString("classification_status") + " / " + rs.getString("classification_urgency")
                    + " / " + String.format(Locale.ROOT,"%.2f",rs.getDouble("classification_confidence"))
                    + " · provedor=" + provider(rs.getString("provider"))
                    + " · pedido=" + (rs.getString("request_detected") == null ? "indisponível" : "true".equals(rs.getString("request_detected")) ? "sim" : "não")
                    + " · Shadow " + rs.getString("decision_state") + " · alerta=" + rs.getBoolean("would_alert")
                    + " resposta=" + rs.getBoolean("would_reply") + " · bloqueio=" + code(rs.getString("blocked_reason"))
                    + (rs.getString("classification_diagnostic") == null ? "" : " · diagnóstico=" + code(rs.getString("classification_diagnostic")));
        }, Timestamp.from(clock.instant()), limit);
        return rows.isEmpty() ? "Nenhuma decisão disponível dentro da retenção." : "Decisões recentes:\n" + String.join("\n\n",rows);
    }

    String detail(List<String> args) {
        if (args.size()!=1 || !args.getFirst().matches("(?i)[0-9a-f]{8}(?:[0-9a-f-]{0,28})?"))
            throw new IllegalArgumentException("ID inválido. Use /decisao <id>.");
        String prefix=args.getFirst().toLowerCase(Locale.ROOT);
        var rows=jdbc.query("""
                select d.*, b.chat_jid, e.result::text as evaluation,
                       t.body as template_body, h.body as hypothetical_body,
                       h.enabled as hypothetical_template_enabled
                  from simulation_decisions d join simulation_batches b on b.batch_id=d.batch_id
                  left join simulation_ai_evaluations e on e.batch_id=d.batch_id
                  left join response_templates t on d.template_id=t.template_id || '@v' || t.version::text
                    and t.enabled and t.identifies_automation
                    and t.situation=case when b.chat_jid like '%@g.us' then 'GROUP_TRIGGER' else 'CONTACT_REQUEST' end
                    and lower(ltrim(t.body)) like 'resposta automática:%'
                    and exists(select 1 from chat_automation_authorizations a where a.chat_jid=b.chat_jid
                        and a.situation=t.situation and a.template_id=t.template_id and a.template_version=t.version)
                  left join lateral (
                    select rt.body, rt.enabled from chat_automation_authorizations a
                      join response_templates rt on rt.template_id=a.template_id and rt.version=a.template_version and rt.situation=a.situation
                     where a.chat_jid=b.chat_jid and rt.identifies_automation
                       and lower(ltrim(rt.body)) like 'resposta automática:%'
                       and a.situation=case when b.chat_jid like '%@g.us' then 'GROUP_TRIGGER' else 'CONTACT_REQUEST' end
                       and e.result->>'requestDetected'='true'
                       and (b.chat_jid not like '%@g.us' or e.result->>'groupTriggerMatched'='true')
                       and e.result->'situations' @> to_jsonb(array[a.situation])
                       and d.classification_status='CLASSIFIED' and d.availability_state is not null
                       and coalesce(d.availability_source,'NO_POLICY') <> 'NO_POLICY'
                     order by rt.version desc,rt.template_id limit 1
                  ) h on true
                 where d.metadata_expires_at>? and d.batch_id::text like ? order by d.batch_id limit 2
                """, (rs,n) -> {
            String urgency=rs.getString("classification_urgency");
            String explanation=switch(urgency) {
                case "LOW" -> "sem prazo iminente; importância não implica urgência";
                case "NORMAL" -> "demanda com prazo regular ou impacto moderado";
                case "HIGH" -> "prazo próximo ou consequência relevante";
                case "CRITICAL" -> "emergência imediata com consequências graves";
                default -> "urgência incerta";
            };
            String evaluation=rs.getString("evaluation");
            String provider="indisponível", situations="indisponível", context="limites de contexto não armazenados";
            String requestDetected="indisponível";
            if(evaluation!=null) {
                var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(evaluation);
                provider=escape(json.path("provider").asString("indisponível"));
                situations=escape(json.path("situations").toString());
                requestDetected=json.has("requestDetected") ? (json.path("requestDetected").asBoolean(false) ? "sim" : "não") : "indisponível";
                int count=json.path("contextCount").asInt(-1), hours=json.path("contextWindowHours").asInt(-1);
                if (count >= 0 && count <= 5 && hours >= 1 && hours <= 168)
                    context="contexto: " + count + " mensagens; janela até " + hours + " h";
            }
            boolean reply=rs.getBoolean("would_reply"), allowed=rs.getBoolean("real_action_allowed"), production=rs.getBoolean("production_mode");
            String body=reply ? rs.getString("template_body") : rs.getString("hypothetical_body");
            String preview=body != null ? (reply ? "Prévia de ação prevista: " : "Prévia hipotética se habilitado: ")
                    + escape(body.length()>500 ? body.substring(0,500) : body)
                    + (!reply && !rs.getBoolean("hypothetical_template_enabled") ? " (template atualmente inativo)" : "")
                    : "Prévia indisponível: template aprovado, autorização da conversa, política ou classificação ausente.";
            return "Decisão " + rs.getString("batch_id") + "\nSituação persistida: " + situations + " · provedor: " + provider
                    + "\nClassificação: " + rs.getString("classification_status") + " / " + urgency + " / "
                    + String.format(Locale.ROOT,"%.2f",rs.getDouble("classification_confidence"))
                    + "\nSolicitação detectada: " + requestDetected
                    + "\n" + context
                    + "\nRubrica geral: " + explanation + "; " + (rs.getString("classification_diagnostic") == null
                        ? "motivo específico não armazenado." : "diagnóstico=" + code(rs.getString("classification_diagnostic")) + ".")
                    + "\nAção prevista: would_alert: " + rs.getBoolean("would_alert") + "; would_reply: " + reply
                    + "\ndecision_reason: " + escape(safe(rs.getString("decision_reason")))
                    + "; blocked_reason: " + escape(safe(rs.getString("blocked_reason")))
                    + "; real_blocked_reason: " + escape(safe(rs.getString("real_blocked_reason")))
                    + "\nproduction_mode: " + production + "; ação real autorizada no registro: " + (allowed && production)
                    + "\n" + preview + "\nPrévia informativa; não autoriza envio automático.";
        },Timestamp.from(clock.instant()),prefix+"%");
        if(rows.isEmpty()) return "Decisão não encontrada ou expirada pela retenção.";
        if(rows.size()>1) return "ID ambíguo; informe mais caracteres.";
        return rows.getFirst();
    }
    private static String code(String value) { return value == null ? "nenhum" : value.matches("[a-zA-Z0-9_-]{1,120}") ? (value.length() > 48 ? value.substring(0,48) : value) : "indisponível"; }
    private static String provider(String value) { return "openrouter".equals(value) || "gemini".equals(value) ? value : "indisponível"; }
    private static boolean safeName(String value) { return value != null && !value.isBlank() && !value.contains("@") && value.replaceAll("\\D", "").length() < 8; }
    private static String safe(String value) { return value==null || value.isBlank() ? "nenhum" : value; }
    private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}

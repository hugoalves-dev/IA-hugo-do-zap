package dev.hugo.watcher.ai;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Versioned backend-controlled authoring scope. Intent detection is deliberately conservative:
 * a policy-only instruction is never paid for or promoted to classification guidance.
 * Mixed requests can produce a classification draft, but policy changes always remain pending.
 */
public final class PromptInstructionScope {
    private static final Pattern NOTIFICATION = Pattern.compile(
            "\\b(notific\\w*|alert\\w*|avis\\w*|prioridad\\w*|expedient\\w*|"
            + "horario comercial|nao lid\\w*|visualizad\\w*|work.only|"
            + "unread|notification\\w*|priority|work hours|work.only)\\b");
    private static final Pattern AUTO_REPLY = Pattern.compile(
            "\\b(resposta\\w* automat\\w*|responder automat\\w*|"
            + "auto.reply|automatic reply|template\\w*|cooldown\\w*|"
            + "autorizar envio|permissao de envio|enviar automaticamente)\\b");
    private static final Pattern CLASSIFICATION = Pattern.compile(
            "\\b(classific\\w*|urgenc\\w*|semantic\\w*|intenc\\w*|"
            + "evidenc\\w*|prompt\\w*|low|high|critical|uncertain|"
            + "modelo\\w*|classifier\\w*|intent\\w*|urgency|"
            + "avaliar necessidade de resposta|reply.recommended)\\b");
    private static final Pattern JC_MARKER = Pattern.compile("\\(\\s*jc\\s*\\)");
    private static final Pattern CH_MARKER = Pattern.compile("(?<![a-z0-9])ch(?![a-z0-9])");

    private PromptInstructionScope() { }

    public static Review inspect(String instructions) {
        String normalized = fold(instructions == null ? "" : instructions);
        boolean marker = JC_MARKER.matcher(normalized).find() || CH_MARKER.matcher(normalized).find();
        boolean notification = marker || NOTIFICATION.matcher(normalized).find();
        boolean reply = AUTO_REPLY.matcher(normalized).find();
        boolean classification = CLASSIFICATION.matcher(normalized).find();
        return new Review(classification || (!notification && !reply), notification, reply);
    }

    public record Review(boolean classification, boolean notificationPolicy, boolean autoReplyPolicy) {
        public boolean policyOnly() { return !classification && (notificationPolicy || autoReplyPolicy); }
        public Feedback feedback(boolean changed) {
            return new Feedback(1, changed ? List.of("CLASSIFICATION_PROMPT_UPDATED") : List.of(),
                    outOfScope(), outOfScope(), warnings(changed));
        }
        private List<String> outOfScope() {
            if (notificationPolicy && autoReplyPolicy) return List.of("NOTIFICATION_POLICY", "AUTO_REPLY_POLICY");
            if (notificationPolicy) return List.of("NOTIFICATION_POLICY");
            if (autoReplyPolicy) return List.of("AUTO_REPLY_POLICY");
            return List.of();
        }
        private List<String> warnings(boolean changed) {
            if (!changed) return List.of("NO_EFFECTIVE_PROMPT_CHANGE");
            if (notificationPolicy && autoReplyPolicy)
                return List.of("POLICY_NOT_CONFIGURED", "AUTO_REPLY_NOT_ENABLED");
            if (notificationPolicy) return List.of("POLICY_NOT_CONFIGURED");
            if (autoReplyPolicy) return List.of("AUTO_REPLY_NOT_ENABLED");
            return List.of();
        }
    }

    /** No-effect includes a proposal that only adds lines disclaiming policy control. */
    public static boolean effectiveChange(String before, String after) {
        return !foldClassification(before).equals(foldClassification(after));
    }

    private static String foldClassification(String prompt) {
        if (prompt == null) return "";
        return fold(prompt.lines()
                .filter(line -> !line.toLowerCase(Locale.ROOT).matches(
                        ".*(?:\\(jc\\)|\\bch\\b|notification policy|notification settings|"
                        + "contact labels|only the notification layer|only by the notification).*"))
                .reduce("", (a, b) -> a + " " + b)).replaceAll("\\s+", " ").strip();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Codes have a fixed vocabulary, so untrusted provider strings cannot be persisted as instructions. */
    public record Feedback(int schemaVersion, List<String> appliedChanges, List<String> outOfScopeRequests,
                           List<String> requiresPolicyConfiguration, List<String> warnings) {
        private static final List<String> APPLIED = List.of("CLASSIFICATION_PROMPT_UPDATED");
        private static final List<String> SCOPES = List.of("NOTIFICATION_POLICY", "AUTO_REPLY_POLICY");
        private static final List<String> WARNINGS = List.of("POLICY_NOT_CONFIGURED",
                "AUTO_REPLY_NOT_ENABLED", "NO_EFFECTIVE_PROMPT_CHANGE");
        public Feedback {
            if (schemaVersion != 1 || !APPLIED.containsAll(appliedChanges)
                    || !SCOPES.containsAll(outOfScopeRequests)
                    || !SCOPES.containsAll(requiresPolicyConfiguration)
                    || !WARNINGS.containsAll(warnings))
                throw new IllegalArgumentException("invalid_prompt_scope_feedback");
            appliedChanges = List.copyOf(appliedChanges);
            outOfScopeRequests = List.copyOf(outOfScopeRequests);
            requiresPolicyConfiguration = List.copyOf(requiresPolicyConfiguration);
            warnings = List.copyOf(warnings);
        }

        public String telegramSummary() {
            StringBuilder message = new StringBuilder("Escopo da proposta (v1): ")
                    .append(appliedChanges.isEmpty() ? "nenhuma alteração de classificação comprovada"
                            : "texto de classificação alterado");
            if (requiresPolicyConfiguration.contains("NOTIFICATION_POLICY"))
                message.append("\n⚠️ Notificações/prioridade NÃO configuradas. Use /regras_notificacao e /regra_notificacao.");
            if (requiresPolicyConfiguration.contains("AUTO_REPLY_POLICY"))
                message.append("\n⚠️ Respostas automáticas NÃO autorizadas nem habilitadas. Configure-as separadamente.");
            if (warnings.contains("NO_EFFECTIVE_PROMPT_CHANGE"))
                message.append("\nNenhuma mudança efetiva de prompt foi identificada.");
            return message.toString();
        }
    }
}

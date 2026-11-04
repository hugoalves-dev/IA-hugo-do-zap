package dev.hugo.watcher.ai;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic lint for operational text; immutable provider instructions remain authoritative. */
@Component
public final class ClassificationPromptValidator {
    public static final int MAX_BODY_LENGTH = 16_000;
    // Conversation data is supplied separately, so runtime substitution is deliberately unsupported.
    public static final Set<String> ALLOWED_PLACEHOLDERS = Set.of();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{|\\$\\{");
    private static final Pattern SAFETY = Pattern.compile(
            "\\b(ignore|override|disregard|bypass|disable|ignorar|ignore|desative|desativar|contorne)\\b.{0,100}\\b(safety|instructions?|authorization|permissions?|consent|budgets?|kill.switch|seguranca|instrucoes|autorizacao|permissoes|consentimento)\\b");
    private static final Pattern SCHEMA = Pattern.compile(
            "\\b(change|replace|ignore|override|alterar|altere|mude|substitua)\\b.{0,60}\\b(schema|json|esquema)\\b");
    private static final Pattern ACTION = Pattern.compile(
            "\\b(send|execute|enable|authorize|enviar|envie|execute|habilite|autorize)\\b.{0,60}\\b(whatsapp|messages?|actions?|tools?|mensagens|acoes|ferramentas)\\b");

    public record ValidationResult(boolean valid, List<String> violationCodes) {
        public ValidationResult { violationCodes = List.copyOf(violationCodes); }
    }

    public ValidationResult validate(String body) {
        List<String> violations = new ArrayList<>();
        if (body == null || body.isBlank()) violations.add("blank_body");
        if (body != null) {
            if (body.length() > MAX_BODY_LENGTH) violations.add("body_too_long");
            if (PLACEHOLDER.matcher(body).find()) violations.add("undefined_placeholder");
            String text = Normalizer.normalize(body, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (SAFETY.matcher(text).find()) violations.add("safety_override");
            if (SCHEMA.matcher(text).find()) violations.add("schema_override");
            if (ACTION.matcher(text).find()) violations.add("real_action_override");
        }
        return new ValidationResult(violations.isEmpty(), violations);
    }
}

package dev.hugo.watcher.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks common contact and identifier formats; it does not detect names or arbitrary sensitive prose. */
final class ClassificationInputMinimizer {
    private static final Pattern JID = Pattern.compile(
            "(?i)(?<![A-Z0-9._+-])[A-Z0-9._+-]{1,80}@(s\\.whatsapp\\.net|g\\.us|lid|broadcast|newsletter)(?![A-Z0-9._-])");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern CPF_CNPJ = Pattern.compile(
            "(?<!\\d)(?:\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}|\\d{11}|\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}|\\d{14})(?!\\d)");
    private static final Pattern LABELED_ID = Pattern.compile(
            "(?i)\\b((?:cpf|cnpj|rg|id|account(?:\\s*(?:number|no\\.?))?|document)\\s*[:#=]?\\s*)[A-Z0-9][A-Z0-9._/-]{3,}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[ .()/-]*)?(?:\\(?\\d{2,4}\\)?[ .()-]*)?\\d{3,5}[ .-]?\\d{4}(?!\\d)");
    private static final Pattern LONG_NUMERIC_IDENTIFIER = Pattern.compile("(?<!\\d)\\d{16,}(?!\\d)");

    private ClassificationInputMinimizer() { }

    static String minimize(String text) {
        String masked = JID.matcher(text).replaceAll("[JID]");
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
        masked = UUID.matcher(masked).replaceAll("[IDENTIFIER]");
        masked = CPF_CNPJ.matcher(masked).replaceAll("[IDENTIFIER]");
        masked = LABELED_ID.matcher(masked).replaceAll("$1[IDENTIFIER]");
        masked = LONG_NUMERIC_IDENTIFIER.matcher(masked).replaceAll("[IDENTIFIER]");
        Matcher numbers = PHONE.matcher(masked);
        StringBuffer output = new StringBuffer();
        while (numbers.find()) {
            String token = numbers.group();
            long digits = token.chars().filter(Character::isDigit).count();
            numbers.appendReplacement(output, Matcher.quoteReplacement(digits >= 8 ? "[PHONE]" : token));
        }
        numbers.appendTail(output);
        return output.toString();
    }
}

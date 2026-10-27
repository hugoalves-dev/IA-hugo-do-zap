package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.simulation.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded and deterministically minimized text passed to a classification provider. */
public record ClassificationInput(List<String> messages) {
    public static final int MAX_MESSAGES = 5;
    public static final int MAX_CHARS_PER_MESSAGE = 1_000;

    public ClassificationInput {
        if (messages == null) messages = List.of();
        messages = messages.stream().filter(java.util.Objects::nonNull)
                .map(String::strip).filter(text -> !text.isEmpty())
                .limit(MAX_MESSAGES)
                .map(ClassificationInputMinimizer::minimize)
                .map(text -> text.length() > MAX_CHARS_PER_MESSAGE ? text.substring(0, MAX_CHARS_PER_MESSAGE) : text)
                .toList();
    }

    public static ClassificationInput fromBatch(Batch batch) {
        if (batch == null || batch.messages() == null) return new ClassificationInput(List.of());
        List<String> text = new ArrayList<>();
        for (IncomingMessage message : batch.messages()) {
            if (message == null || message.fromMe() || message.history()) continue;
            String extracted = extractText(message.content());
            if (!extracted.isBlank()) text.add(extracted);
        }
        return new ClassificationInput(text);
    }

    private static String extractText(Map<String, Object> content) {
        Map<?, ?> current = content;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Object conversation = current.get("conversation");
            if (conversation instanceof String text) return text;
            Object extended = current.get("extendedTextMessage");
            if (extended instanceof Map<?, ?> map && map.get("text") instanceof String text) return text;
            for (String type : List.of("imageMessage", "videoMessage", "documentMessage")) {
                Object media = current.get(type);
                if (media instanceof Map<?, ?> map && map.get("caption") instanceof String caption) return caption;
            }
            Object nested = null;
            for (String wrapper : List.of("ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2",
                    "viewOnceMessageV2Extension", "documentWithCaptionMessage")) {
                if (current.get(wrapper) instanceof Map<?, ?> map) {
                    nested = map.get("message");
                    break;
                }
            }
            current = nested instanceof Map<?, ?> map ? map : null;
        }
        return "";
    }
}

package dev.hugo.watcher.policy;

public record ResponseTemplate(String id, int version, Situation situation, String body, boolean identifiesAutomation) {
    public ResponseTemplate {
        if (id == null || id.isBlank() || version < 1 || situation == null || body == null || body.isBlank())
            throw new IllegalArgumentException("valid versioned template is required");
    }
    public String reference() { return id + "@v" + version; }

    public boolean isPreapprovedAndDisclosed() {
        String prefix = "Resposta automática:";
        return identifiesAutomation && body.stripLeading().regionMatches(true, 0, prefix, 0, prefix.length());
    }
}

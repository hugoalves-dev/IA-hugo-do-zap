package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores only allowlisted, versioned feedback, bound to an exact draft prompt digest. */
@Repository
public class PromptScopeFeedbackRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public PromptScopeFeedbackRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(UUID draft, long version, String body, PromptInstructionScope.Feedback feedback, Instant now) {
        if (draft == null || version <= 0 || body == null || feedback == null || now == null)
            throw new IllegalArgumentException("invalid_scope_feedback");
        String serialized = JSON.writeValueAsString(java.util.Map.of(
                "appliedChanges", feedback.appliedChanges(),
                "outOfScopeRequests", feedback.outOfScopeRequests(),
                "requiresPolicyConfiguration", feedback.requiresPolicyConfiguration(),
                "warnings", feedback.warnings()));
        jdbc.update("""
                insert into prompt_scope_feedback(draft_id,draft_version,schema_version,prompt_hash,feedback,created_at)
                values (?,?,?,?,?::jsonb,?)
                """, draft, version, feedback.schemaVersion(),
                ClassificationPromptRepository.contentHash(body), serialized, Timestamp.from(now));
    }

    public Optional<PromptInstructionScope.Feedback> forDraft(UUID draftId, long ownerId, String currentBody) {
        if (draftId == null || ownerId <= 0 || currentBody == null) return Optional.empty();
        var rows = jdbc.query("""
                select f.schema_version,f.feedback::text from prompt_scope_feedback f
                join classification_prompt_drafts d on d.id=f.draft_id
                where f.draft_id=? and d.owner_id=? and f.prompt_hash=?
                order by f.draft_version desc limit 1
                """, (rs, row) -> decode(rs.getInt(1), rs.getString(2)), draftId, ownerId,
                ClassificationPromptRepository.contentHash(currentBody));
        return rows.stream().findFirst();
    }

    private static PromptInstructionScope.Feedback decode(int version, String json) {
        JsonNode data = JSON.readTree(json);
        return new PromptInstructionScope.Feedback(version, codes(data, "appliedChanges"),
                codes(data, "outOfScopeRequests"), codes(data, "requiresPolicyConfiguration"),
                codes(data, "warnings"));
    }

    private static List<String> codes(JsonNode data, String field) {
        JsonNode array = data.path(field);
        if (!array.isArray() || array.size() > 8) throw new IllegalArgumentException("invalid_scope_feedback");
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isString()) throw new IllegalArgumentException("invalid_scope_feedback");
            result.add(node.asString());
        }
        return List.copyOf(result);
    }
}

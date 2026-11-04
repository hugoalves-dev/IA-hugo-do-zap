package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ai.ClassificationResult;
import dev.hugo.watcher.policy.DecisionPolicy;
import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.ResponseTemplate;
import dev.hugo.watcher.policy.Situation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriageCalibrationFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Test void deterministicTriageMatchesSyntheticAnonymizedCorpus() throws Exception {
        var fixture = JSON.readTree(getClass().getResourceAsStream("/fixtures/triage-anonymized-cases.json"));
        assertEquals(8, fixture.size());
        DecisionPolicy policy = policy();
        DecisionEngine engine = new DecisionEngine();
        int truePositive = 0, falsePositive = 0, falseNegative = 0;

        for (var item : fixture) {
            boolean available = item.path("available").asBoolean();
            String chat = "group".equals(item.path("scope").asString()) ? "triage@g.us" : "triage@s.whatsapp.net";
            Set<Situation> situations = item.path("situations").valueStream()
                    .map(value -> Situation.valueOf(value.asString())).collect(java.util.stream.Collectors.toSet());
            List<ClassificationResult.Evidence> evidence = item.path("evidence").valueStream()
                    .map(value -> ClassificationResult.Evidence.valueOf(value.asString())).toList();
            Classification classification = available
                    ? Classification.classified(situations, item.path("requestDetected").asBoolean(),
                        item.path("notificationRecommended").asBoolean(), item.path("replyRecommended").asBoolean(),
                        evidence, item.path("explicitPersonalReference").asBoolean(),
                        item.path("groupTriggerMatched").asBoolean(),
                        ClassificationResult.Urgency.valueOf(item.path("urgency").asString()), 0.9, null)
                    : Classification.unavailable(item.path("diagnostic").asString());
            var batch = new Batch(java.util.UUID.randomUUID(), chat,
                    chat.endsWith("@g.us") ? "participant@s.whatsapp.net" : "", List.of());
            TriageOutcome expected = TriageOutcome.valueOf(item.path("expectedOutcome").asString());
            TriageOutcome actual = engine.decide(batch, classification,
                    new EffectivePolicy(Map.of(), null, policy), NOW).triageOutcome();
            assertEquals(expected, actual, item.path("id").asString());

            boolean expectedNotify = expected == TriageOutcome.NOTIFY_ONLY || expected == TriageOutcome.NOTIFY_AND_REPLY;
            boolean actualNotify = actual == TriageOutcome.NOTIFY_ONLY || actual == TriageOutcome.NOTIFY_AND_REPLY;
            if (actualNotify && expectedNotify) truePositive++;
            else if (actualNotify) falsePositive++;
            else if (expectedNotify) falseNegative++;
        }

        double precision = (double) truePositive / (truePositive + falsePositive);
        double recall = (double) truePositive / (truePositive + falseNegative);
        assertEquals(1.0, precision);
        assertEquals(1.0, recall);
        assertEquals(0, falseNegative, "the deterministic triage mapping must not drop a corpus notification");
    }

    private static DecisionPolicy policy() {
        var authorized = EnumSet.allOf(Situation.class);
        var templates = new EnumMap<Situation, ResponseTemplate>(Situation.class);
        templates.put(Situation.CONTACT_REQUEST, new ResponseTemplate("contact_v1", 1, Situation.CONTACT_REQUEST,
                "Resposta automática: retorno autorizado.", true));
        templates.put(Situation.GROUP_TRIGGER, new ResponseTemplate("group_v1", 1, Situation.GROUP_TRIGGER,
                "Resposta automática: grupo autorizado.", true));
        return new DecisionPolicy(true, true, true, true, authorized, templates,
                Duration.ZERO, Duration.ZERO, null, null);
    }
}

package dev.hugo.watcher.simulation;

import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.DecisionPolicy;
import dev.hugo.watcher.policy.ResponseTemplate;
import dev.hugo.watcher.policy.Situation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DecisionEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private final DecisionEngine engine = new DecisionEngine();

    @Test void isolatedGreetingDoesNotAuthorizeReply() {
        Decision decision = engine.decide(batch(false), Classification.greeting(), policy(true), NOW);
        assertFalse(decision.wouldReply());
        assertEquals("no_request_detected", decision.reason());
        assertEquals(DecisionStatus.NO_ACTION, decision.status());
        assertEquals(TriageOutcome.IGNORE, decision.triageOutcome());
        assertEquals("no_notification_recommended", decision.triageReason());
    }

    @Test void unavailableClassifierFailsClosedWithAuditableReason() {
        Decision decision = engine.decide(batch(false), Classification.unavailable(), policy(true), NOW);
        assertFalse(decision.wouldAlert());
        assertFalse(decision.wouldReply());
        assertEquals("classification_unavailable", decision.blockedReason());
        assertEquals(DecisionStatus.BLOCKED, decision.status());
        assertEquals(TriageOutcome.UNDECIDED, decision.triageOutcome());
    }

    @Test void inconclusiveProviderResponseHasDistinctFailClosedReason() {
        var classification = new Classification(Set.of(), false, false, false, false,
                dev.hugo.watcher.ai.ClassificationResult.Urgency.UNCERTAIN, 0.3,
                dev.hugo.watcher.ai.ClassificationResult.Status.UNCERTAIN, null);
        Decision decision = engine.decide(batch(false), classification, policy(true), NOW);
        assertEquals("classification_uncertain", decision.blockedReason());
        assertFalse(decision.realActionAllowed());
    }

    @Test void contactRequestNeedsExplicitAuthorizationAndTemplate() {
        var classification = Classification.contactRequest();
        Decision unauthorized = engine.decide(batch(false), classification, policy(true, Set.of(), true, true), NOW);
        assertFalse(unauthorized.wouldReply());
        assertEquals(TriageOutcome.NOTIFY_AND_REPLY, unauthorized.triageOutcome());
        assertFalse(engine.decide(batch(false), classification, policy(true, Set.of(Situation.CONTACT_REQUEST), false, true), NOW).wouldReply());
        assertTrue(engine.decide(batch(false), classification,
                policy(true, Set.of(Situation.CONTACT_REQUEST), true, true), NOW).wouldReply());
    }

    @Test void groupTriggerAloneDoesNotCreateCriticalPersonalReference() {
        Decision decision = engine.decide(batch(true), Classification.groupTriggerCritical(false),
                policy(true, Set.of(Situation.GROUP_TRIGGER, Situation.CRITICAL_URGENCY), true, true), NOW);
        assertFalse(decision.wouldAlert());
        assertEquals("critical_reference_required", decision.blockedReason());
        assertEquals(DecisionStatus.BLOCKED, decision.status());
    }

    @Test void groupReplyNeedsGroupAuthorizationAndVersionedTemplate() {
        var classification = Classification.groupTriggerCritical(true);
        assertFalse(engine.decide(batch(true), classification,
                policy(true, Set.of(), true, true), NOW).wouldReply());
        assertFalse(engine.decide(batch(true), classification,
                policy(true, Set.of(Situation.GROUP_TRIGGER), false, true), NOW).wouldReply());
        var settings = new DecisionPolicy(true, true, true, true, Set.of(Situation.GROUP_TRIGGER),
                Map.of(Situation.GROUP_TRIGGER, new ResponseTemplate("group_trigger_v1", 1,
                        Situation.GROUP_TRIGGER, "Resposta automática: Grupo autorizado.", true)),
                Duration.ofMinutes(5), Duration.ofMinutes(60), null, null);
        Decision decision = engine.decide(batch(true), classification, new EffectivePolicy(Map.of(), null, settings), NOW);
        assertTrue(decision.wouldReply());
        assertEquals("group_trigger_v1@v1", decision.templateReference());
    }

    @Test void criticalUrgencyRequiresExplicitPersonalReference() {
        Decision decision = engine.decide(batch(false), Classification.criticalUrgency(false),
                policy(true, Set.of(Situation.CRITICAL_URGENCY), true, true), NOW);
        assertFalse(decision.wouldAlert());
        assertEquals("critical_reference_required", decision.blockedReason());
    }

    @Test void simulationCooldownBlocksSimulationAndKillSwitchBlocksRealAction() {
        var settings = new DecisionPolicy(true, true, true, true,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v1", 1, Situation.CONTACT_REQUEST, "Resposta automática: Retorno autorizado.", true)),
                Duration.ofMinutes(5), Duration.ofMinutes(60), NOW.minusSeconds(60), NOW.minusSeconds(3600));
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertFalse(decision.wouldReply());
        assertEquals("simulation_cooldown", decision.blockedReason());
        assertFalse(decision.realActionAllowed());
        assertEquals("kill_switch_enabled", decision.realBlockedReason());
    }

    @Test void realCooldownDoesNotBlockSimulationCooldown() {
        var settings = new DecisionPolicy(true, true, true, false,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v1", 1, Situation.CONTACT_REQUEST, "Resposta automática: Retorno autorizado.", true)),
                Duration.ofMinutes(5), Duration.ofMinutes(60), null, NOW.minusSeconds(60));
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertTrue(decision.wouldReply());
        assertFalse(decision.realActionAllowed());
        assertEquals("real_cooldown", decision.realBlockedReason());
    }

    @Test void simulationCooldownBlocksAlertsAndRepliesTogether() {
        var settings = new DecisionPolicy(true, true, true, false,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v1", 1, Situation.CONTACT_REQUEST, "Resposta automática: Retorno autorizado.", true)),
                Duration.ofMinutes(5), Duration.ofMinutes(60), NOW.minusSeconds(60), null);
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertFalse(decision.wouldAlert());
        assertFalse(decision.wouldReply());
        assertEquals("simulation_cooldown", decision.blockedReason());
    }

    @Test void killSwitchBlocksRealActionWithoutSuppressingShadowDecision() {
        Decision decision = engine.decide(batch(false), Classification.contactRequest(), policy(true), NOW);
        assertTrue(decision.wouldReply());
        assertFalse(decision.realActionAllowed());
        assertEquals("kill_switch_enabled", decision.realBlockedReason());
    }

    @Test void automationTemplateMustDiscloseAutomatedReply() {
        var settings = new DecisionPolicy(true, true, true, false,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v1", 1, Situation.CONTACT_REQUEST, "Oi", true)),
                Duration.ZERO, Duration.ofMinutes(60), null, null);
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertFalse(decision.wouldReply());
        assertEquals("template_disclosure_required", decision.blockedReason());
    }

    @Test void disclosureTextStillNeedsPreapprovedTemplateFlag() {
        var settings = new DecisionPolicy(true, true, true, false,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v2", 2, Situation.CONTACT_REQUEST, "Resposta automática: Oi", false)),
                Duration.ZERO, Duration.ofMinutes(60), null, null);
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertFalse(decision.wouldReply());
        assertEquals("template_disclosure_required", decision.blockedReason());
    }

    @Test void simulationCooldownDoesNotDenyIndependentlyEligibleRealAction() {
        var settings = new DecisionPolicy(true, true, true, false,
                Set.of(Situation.CONTACT_REQUEST), Map.of(Situation.CONTACT_REQUEST,
                new ResponseTemplate("contact_request_v1", 1, Situation.CONTACT_REQUEST, "Resposta automática: Retorno autorizado.", true)),
                Duration.ofMinutes(5), Duration.ofMinutes(60), NOW.minusSeconds(60), null);
        Decision decision = engine.decide(batch(false), Classification.contactRequest(),
                new EffectivePolicy(Map.of(), null, settings), NOW);
        assertFalse(decision.wouldReply());
        assertFalse(decision.wouldAlert());
        assertEquals("simulation_cooldown", decision.blockedReason());
        assertTrue(decision.realActionAllowed());
        assertNull(decision.realBlockedReason());
    }

    @Test void incompleteAvailabilityBlocksEvenWhenAiIsOff() {
        EffectivePolicy effective = new EffectivePolicy(Map.of(), null, DecisionPolicy.disabled(),
                new dev.hugo.watcher.policy.Availability(dev.hugo.watcher.policy.AvailabilityState.UNAVAILABLE,
                        dev.hugo.watcher.policy.AvailabilitySource.NO_POLICY,"incomplete"));
        assertEquals("availability_policy_incomplete", engine.decide(batch(false), Classification.unavailable(), effective, NOW).blockedReason());
    }

    @Test void unavailableOwnerAllowsOnlyPersonallyReferencedCriticalAlert() {
        var unavailable = new dev.hugo.watcher.policy.Availability(dev.hugo.watcher.policy.AvailabilityState.UNAVAILABLE,
                dev.hugo.watcher.policy.AvailabilitySource.TEMPORARY_OVERRIDE,"busy");
        var ordinary = new EffectivePolicy(Map.of(), null, policy(true).decisionPolicy(), unavailable);
        assertFalse(engine.decide(batch(false), Classification.contactRequest(), ordinary, NOW).wouldAlert());
        var critical = new EffectivePolicy(Map.of(), null,
                policy(true,Set.of(Situation.CRITICAL_URGENCY),true,true).decisionPolicy(), unavailable);
        assertFalse(engine.decide(batch(false), Classification.criticalUrgency(false), critical, NOW).wouldAlert());
        assertTrue(engine.decide(batch(false), Classification.criticalUrgency(true), critical, NOW).wouldAlert());
    }

    @Test void lowUrgencyRelevantRequestRemainsNotifyOnlyWhenAlertActionsAreDisabled() {
        var classification = Classification.classified(Set.of(Situation.CONTACT_REQUEST), true, true, false,
                java.util.List.of(dev.hugo.watcher.ai.ClassificationResult.Evidence.EXPLICIT_REQUEST), false, false,
                dev.hugo.watcher.ai.ClassificationResult.Urgency.LOW, 0.91, null);
        var settings = new DecisionPolicy(true, false, false, true, Set.of(), Map.of(),
                Duration.ZERO, Duration.ZERO, null, null);

        Decision decision = engine.decide(batch(false), classification,
                new EffectivePolicy(Map.of(), null, settings), NOW);

        assertEquals(TriageOutcome.NOTIFY_ONLY, decision.triageOutcome());
        assertTrue(decision.notificationRecommended());
        assertFalse(decision.replyRecommended());
        assertFalse(decision.wouldAlert());
        assertFalse(decision.wouldReply());
        assertFalse(decision.realActionAllowed());
    }

    @Test void notifyAndReplyRecommendationIsPreservedWhenAutomationAndTemplateAreBlocked() {
        var classification = Classification.classified(Set.of(Situation.CONTACT_REQUEST), true, true, true,
                java.util.List.of(dev.hugo.watcher.ai.ClassificationResult.Evidence.EXPLICIT_REQUEST), false, false,
                dev.hugo.watcher.ai.ClassificationResult.Urgency.NORMAL, 0.91, null);
        var settings = new DecisionPolicy(true, false, false, true, Set.of(), Map.of(),
                Duration.ZERO, Duration.ZERO, null, null);

        Decision decision = engine.decide(batch(false), classification,
                new EffectivePolicy(Map.of(), null, settings), NOW);

        assertEquals(TriageOutcome.NOTIFY_AND_REPLY, decision.triageOutcome());
        assertEquals("notification_and_reply_recommended", decision.triageReason());
        assertTrue(decision.notificationRecommended());
        assertTrue(decision.replyRecommended());
        assertFalse(decision.wouldAlert());
        assertFalse(decision.wouldReply());
        assertTrue(decision.triageBlockers().contains("auto_reply_disabled"));
        assertTrue(decision.triageBlockers().contains("conversation_authorization_required"));
        assertTrue(decision.triageBlockers().contains("response_template_missing"));
        assertFalse(decision.realActionAllowed());
    }

    @Test void groupReplyRecommendationWithoutVerifiedTriggerNeverBecomesReply() {
        var classification = Classification.classified(Set.of(Situation.GROUP_TRIGGER), true, true, true,
                java.util.List.of(dev.hugo.watcher.ai.ClassificationResult.Evidence.EXPLICIT_REQUEST), false, false,
                dev.hugo.watcher.ai.ClassificationResult.Urgency.NORMAL, 0.91, null);
        var settings = new DecisionPolicy(true, true, true, true, Set.of(Situation.GROUP_TRIGGER),
                Map.of(Situation.GROUP_TRIGGER, new ResponseTemplate("group_v1", 1, Situation.GROUP_TRIGGER,
                        "Resposta automática: Grupo autorizado.", true)),
                Duration.ZERO, Duration.ZERO, null, null);

        Decision decision = engine.decide(batch(true), classification,
                new EffectivePolicy(Map.of(), null, settings), NOW);

        assertEquals(TriageOutcome.NOTIFY_AND_REPLY, decision.triageOutcome());
        assertFalse(decision.wouldReply());
        assertTrue(decision.triageBlockers().contains("group_trigger_missing"));
    }

    private static Batch batch(boolean group) {
        return new Batch(java.util.UUID.randomUUID(), group ? "group@g.us" : "contact@s.whatsapp.net",
                group ? "participant@s.whatsapp.net" : "", java.util.List.of());
    }

    private static EffectivePolicy policy(boolean enabled) {
        return policy(enabled, Set.of(Situation.CONTACT_REQUEST), true, true);
    }

    private static EffectivePolicy policy(boolean enabled, Set<Situation> authorized, boolean hasTemplate, boolean killSwitch) {
        return new EffectivePolicy(Map.of(), null, new DecisionPolicy(enabled, enabled, enabled, killSwitch, authorized,
                hasTemplate ? Map.of(Situation.CONTACT_REQUEST, new ResponseTemplate("contact_request_v1", 1,
                        Situation.CONTACT_REQUEST, "Resposta automática: Retorno autorizado.", true)) : Map.of(),
                Duration.ofMinutes(5), Duration.ofMinutes(60), null, null));
    }
}

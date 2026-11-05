package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptInstructionScopeTest {
    @Test void onlyPolicyInstructionsNeverRequirePaidClassificationGeneration() {
        var scope = PromptInstructionScope.inspect(
                "Contacts marked (JC) must get unread notifications during work hours; CH has priority.");
        assertTrue(scope.policyOnly());
        assertFalse(scope.classification());
        assertEquals(1, scope.feedback(false).schemaVersion());
        assertEquals(java.util.List.of("NOTIFICATION_POLICY"), scope.feedback(false).outOfScopeRequests());
        assertTrue(scope.feedback(false).telegramSummary().contains("NÃO configuradas"));

        var reply = PromptInstructionScope.inspect("Enable automatic replies with a template and cooldown");
        assertTrue(reply.policyOnly());
        assertEquals(java.util.List.of("AUTO_REPLY_POLICY"), reply.feedback(false).requiresPolicyConfiguration());
    }

    @Test void mixedRequestRetainsClassificationScopeButNeverClaimsPolicyApplied() {
        var scope = PromptInstructionScope.inspect(
                "Clarify classification urgency, and prioritize contacts CH without changing reply permissions.");
        assertTrue(scope.classification());
        assertFalse(scope.policyOnly());
        assertTrue(scope.notificationPolicy());
        var feedback = scope.feedback(true);
        assertEquals(java.util.List.of("CLASSIFICATION_PROMPT_UPDATED"), feedback.appliedChanges());
        assertEquals(java.util.List.of("NOTIFICATION_POLICY"), feedback.outOfScopeRequests());
        assertEquals(java.util.List.of("POLICY_NOT_CONFIGURED"), feedback.warnings());

        var classification = PromptInstructionScope.inspect("Clarify intent, urgency, and evidence.");
        assertTrue(classification.classification());
        assertFalse(classification.notificationPolicy());
        assertTrue(classification.feedback(true).outOfScopeRequests().isEmpty());
    }

    @Test void unchangedOrOnlyOutOfScopeExplanationIsNotAnEffectiveChange() {
        String before = "Evaluate urgency from the content.";
        assertFalse(PromptInstructionScope.effectiveChange(before, "  Evaluate urgency from the content. \n"));
        assertFalse(PromptInstructionScope.effectiveChange(before, before
                + "\nContact (JC) notifications are handled only by the notification layer."));
        assertTrue(PromptInstructionScope.effectiveChange(before, "Evaluate urgency from the deadline."));
    }

    @Test void feedbackRejectsUnknownProviderSuppliedCodes() {
        assertThrows(IllegalArgumentException.class, () -> new PromptInstructionScope.Feedback(
                2, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PromptInstructionScope.Feedback(
                1, java.util.List.of("ENABLE_AUTOREPLY"), java.util.List.of(), java.util.List.of(), java.util.List.of()));
    }
}

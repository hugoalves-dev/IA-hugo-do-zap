package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ai.ClassificationResult;
import dev.hugo.watcher.policy.DecisionPolicy;
import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.ResponseTemplate;
import dev.hugo.watcher.policy.Situation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public final class DecisionEngine {
    public Decision decide(Batch batch, Classification classification, EffectivePolicy policy, Instant now) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        boolean notificationRecommended = classification.notificationRecommended();
        boolean replyRecommended = classification.replyRecommended();

        if (policy.availability() != null
                && policy.availability().source() == dev.hugo.watcher.policy.AvailabilitySource.NO_POLICY)
            return undecided(classification, "availability_policy_incomplete", List.of("availability_policy_incomplete"));
        DecisionPolicy settings = policy.decisionPolicy();
        if (settings == null || !settings.aiClassificationEnabled())
            return undecided(classification, "ai_classification_disabled", List.of("ai_classification_disabled"));
        if (!classification.available() || classification.status() != ClassificationResult.Status.CLASSIFIED) {
            String reason = classification.status() == ClassificationResult.Status.UNCERTAIN
                    ? "classification_uncertain" : "classification_unavailable";
            return undecided(classification, reason, List.of(reason));
        }

        TriageOutcome triage = !notificationRecommended ? TriageOutcome.IGNORE
                : replyRecommended ? TriageOutcome.NOTIFY_AND_REPLY : TriageOutcome.NOTIFY_ONLY;
        String triageReason = triageReason(classification, triage);
        List<String> blockers = new ArrayList<>();
        boolean critical = classification.situations().contains(Situation.CRITICAL_URGENCY)
                || classification.urgency() == ClassificationResult.Urgency.CRITICAL;
        if (critical && !classification.explicitPersonalReference()) {
            blockers.add("critical_reference_required");
            return decision(false, false, null, "critical_reference_required", "critical_reference_required",
                    false, "critical_reference_required", triage, notificationRecommended,
                    replyRecommended, triageReason, blockers);
        }

        boolean authorized = classification.situations().stream().anyMatch(settings.authorizedSituations()::contains);
        boolean group = batch.chatJid().endsWith("@g.us");
        boolean groupTrigger = !group || classification.groupTriggerMatched();
        boolean ownerAvailable = policy.availability() == null
                || policy.availability().state() == dev.hugo.watcher.policy.AvailabilityState.AVAILABLE;
        boolean alert = notificationRecommended
                && (ownerAvailable || critical && classification.explicitPersonalReference())
                && settings.intelligentAlertsEnabled() && authorized && groupTrigger;
        if (notificationRecommended && !alert) {
            if (!settings.intelligentAlertsEnabled()) blockers.add("intelligent_alerts_disabled");
            if (!ownerAvailable && !(critical && classification.explicitPersonalReference())) blockers.add("owner_unavailable");
            if (!authorized) blockers.add("conversation_authorization_required");
            if (!groupTrigger) blockers.add("group_trigger_missing");
        }

        ResponseTemplate selected = null;
        boolean reply = false;
        String blocked = null;
        String reason = alert ? "authorized_alert" : "no_authorized_action";
        Situation replySituation = batch.chatJid().endsWith("@g.us")
                ? Situation.GROUP_TRIGGER : Situation.CONTACT_REQUEST;
        if (replyRecommended) {
            if (!settings.autoReplyEnabled()) blockers.add("auto_reply_disabled");
            if (!classification.requestDetected() || !classification.situations().contains(replySituation))
                blockers.add("reply_situation_not_detected");
            if (!settings.authorizedSituations().contains(replySituation)) blockers.add("conversation_authorization_required");
            if (batch.chatJid().endsWith("@g.us") && !classification.groupTriggerMatched())
                blockers.add("group_trigger_missing");
            selected = settings.templates().get(replySituation);
            if (selected == null || selected.situation() != replySituation) {
                blockers.add("response_template_missing");
                selected = null;
            } else if (!selected.isPreapprovedAndDisclosed()) {
                blockers.add("template_disclosure_required");
                selected = null;
            }
            if (settings.autoReplyEnabled() && classification.requestDetected()
                    && classification.situations().contains(replySituation)
                    && settings.authorizedSituations().contains(replySituation)
                    && (!batch.chatJid().endsWith("@g.us") || classification.groupTriggerMatched())
                    && selected != null) {
                reply = true;
                reason = "authorized_reply";
            }
        } else if (!classification.requestDetected()) {
            reason = triage == TriageOutcome.IGNORE ? "no_request_detected" : "no_authorized_action";
        }

        String realBlocked = null;
        boolean realAllowed = alert || reply;
        if (settings.killSwitchEnabled()) {
            realAllowed = false;
            realBlocked = "kill_switch_enabled";
        } else if (!settings.aiClassificationEnabled()
                || (!settings.intelligentAlertsEnabled() && !settings.autoReplyEnabled())) {
            realAllowed = false;
            realBlocked = "feature_disabled";
        } else if (!authorized) {
            realAllowed = false;
            realBlocked = "authorization_required";
        } else if (cooldownActive(settings.lastRealActionAt(), settings.realCooldown(), now)) {
            realAllowed = false;
            realBlocked = "real_cooldown";
        }
        if ((alert || reply) && cooldownActive(settings.lastSimulationActionAt(), settings.simulationCooldown(), now)) {
            alert = false;
            reply = false;
            selected = null;
            reason = "simulation_cooldown";
            blocked = "simulation_cooldown";
            blockers.add("simulation_cooldown");
        }
        if (blocked == null && !blockers.isEmpty()) blocked = blockers.getFirst();
        return decision(alert, reply, selected == null ? null : selected.reference(), reason, blocked,
                realAllowed, realBlocked, triage, notificationRecommended, replyRecommended,
                triageReason, blockers);
    }

    private static String triageReason(Classification classification, TriageOutcome outcome) {
        if (outcome == TriageOutcome.IGNORE) {
            if (classification.evidence().contains(ClassificationResult.Evidence.ALREADY_RESOLVED)) return "conversation_resolved";
            if (classification.evidence().contains(ClassificationResult.Evidence.PROMOTIONAL_CONTENT)) return "promotional_content";
            if (classification.evidence().contains(ClassificationResult.Evidence.NO_ACTION_NEEDED)) return "no_action_needed";
            return "no_notification_recommended";
        }
        if (outcome == TriageOutcome.NOTIFY_ONLY)
            return classification.requestDetected() ? "notification_relevant_reply_not_recommended" : "notification_recommended";
        if (outcome == TriageOutcome.NOTIFY_AND_REPLY) return "notification_and_reply_recommended";
        return classification.diagnostic() == null ? "classification_unavailable" : classification.diagnostic();
    }

    private static Decision undecided(Classification classification, String reason, List<String> blockers) {
        return decision(false, false, null, reason, reason, false, reason, TriageOutcome.UNDECIDED,
                classification.available() && classification.notificationRecommended(),
                classification.available() && classification.replyRecommended(), reason, blockers);
    }

    private static Decision decision(boolean alert, boolean reply, String template, String reason, String blocked,
                                     boolean realAllowed, String realBlocked, TriageOutcome triage,
                                     boolean notificationRecommended, boolean replyRecommended,
                                     String triageReason, List<String> blockers) {
        DecisionStatus status = alert || reply ? DecisionStatus.WOULD_ACT
                : blocked != null ? DecisionStatus.BLOCKED : DecisionStatus.NO_ACTION;
        return new Decision(alert, reply, template, reason, blocked, realAllowed, realBlocked, status,
                triage, notificationRecommended, replyRecommended, triageReason, blockers);
    }

    private static boolean cooldownActive(Instant last, java.time.Duration cooldown, Instant now) {
        return last != null && now.isBefore(last.plus(cooldown));
    }
}

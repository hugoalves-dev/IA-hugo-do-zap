package dev.hugo.watcher.simulation;

import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.DecisionPolicy;
import dev.hugo.watcher.policy.ResponseTemplate;
import dev.hugo.watcher.policy.Situation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public final class DecisionEngine {
    public Decision decide(Batch batch, Classification classification, EffectivePolicy policy, Instant now) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        DecisionPolicy settings = policy.decisionPolicy();
        if (settings == null || !settings.aiClassificationEnabled())
            return blocked("ai_classification_disabled", "ai_classification_disabled");
        if (!classification.available())
            return blocked("classification_unavailable", "classification_unavailable");

        boolean critical = classification.situations().contains(Situation.CRITICAL_URGENCY);
        if (critical && !classification.explicitPersonalReference())
            return blocked("critical_reference_required", "critical_reference_required");
        if (classification.requestDetected()
                && cooldownActive(settings.lastSimulationActionAt(), settings.simulationCooldown(), now)) {
            String realBlocked = settings.killSwitchEnabled() ? "kill_switch_enabled"
                    : cooldownActive(settings.lastRealActionAt(), settings.realCooldown(), now) ? "real_cooldown" : "simulation_cooldown";
            return new Decision(false, false, null, "simulation_cooldown", "simulation_cooldown", false, realBlocked);
        }

        boolean alert = settings.intelligentAlertsEnabled()
                && classification.requestDetected()
                && classification.situations().stream().anyMatch(settings.authorizedSituations()::contains);
        ResponseTemplate selected = null;
        boolean reply = false;
        String blocked = null;
        String reason = alert ? "authorized_alert" : "no_authorized_action";

        Situation replySituation = batch.chatJid().endsWith("@g.us")
                ? Situation.GROUP_TRIGGER : Situation.CONTACT_REQUEST;
        if (settings.autoReplyEnabled() && classification.requestDetected()
                && classification.situations().contains(replySituation)
                && settings.authorizedSituations().contains(replySituation)
                && (!batch.chatJid().endsWith("@g.us") || classification.groupTriggerMatched())) {
            selected = settings.templates().get(replySituation);
            if (selected == null || selected.situation() != replySituation) {
                blocked = "response_template_missing";
                reason = "reply_template_required";
            } else if (!selected.identifiesAutomation()) {
                blocked = "template_disclosure_required";
                reason = "reply_template_not_disclosed";
                selected = null;
            } else if (cooldownActive(settings.lastSimulationActionAt(), settings.simulationCooldown(), now)) {
                blocked = "simulation_cooldown";
                reason = "reply_cooldown";
                selected = null;
            } else {
                reply = true;
                reason = "authorized_reply";
            }
        } else if (!classification.requestDetected()) {
            reason = "no_request_detected";
        }

        String realBlocked = null;
        boolean realAllowed = alert || reply;
        if (settings.killSwitchEnabled()) {
            realAllowed = false;
            realBlocked = "kill_switch_enabled";
        } else if (!settings.aiClassificationEnabled() || (!settings.intelligentAlertsEnabled() && !settings.autoReplyEnabled())) {
            realAllowed = false;
            realBlocked = "feature_disabled";
        } else if (!settings.authorizedSituations().stream().anyMatch(classification.situations()::contains)) {
            realAllowed = false;
            realBlocked = "authorization_required";
        } else if (cooldownActive(settings.lastRealActionAt(), settings.realCooldown(), now)) {
            realAllowed = false;
            realBlocked = "real_cooldown";
        }
        return new Decision(alert, reply, selected == null ? null : selected.reference(), reason, blocked, realAllowed, realBlocked);
    }

    private static boolean cooldownActive(Instant last, java.time.Duration cooldown, Instant now) {
        return last != null && now.isBefore(last.plus(cooldown));
    }

    private static Decision blocked(String reason, String blocked) {
        return new Decision(false, false, null, reason, blocked, false, reason);
    }
}

package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroupNotificationEligibilityTest {
    private static final Instant NOW = Instant.parse("2026-11-10T20:30:00Z");

    @Test void ordinaryGroupActivityAndGenericQuestionsAreNotTriggers() {
        assertFalse(GroupNotificationEligibility.isTriggered(false, false, false));
        assertFalse(GroupNotificationEligibility.isTriggered(false, false, false));
    }

    @Test void onlyVerifiedStructuralEvidenceOrExplicitGroupRuleTriggers() {
        assertTrue(GroupNotificationEligibility.isTriggered(true, false, false));
        assertTrue(GroupNotificationEligibility.isTriggered(false, true, false));
        assertTrue(GroupNotificationEligibility.isTriggered(false, false, true));
    }

    @Test void genericQuestionDoesNotGrantPermissionAndMentionMustMatchOwnerMetadata() {
        assertFalse(GroupNotificationEligibility.hasOwnerMention(
                Map.of("conversation", "Can someone help?"), "owner@s.whatsapp.net"));
        assertFalse(GroupNotificationEligibility.hasOwnerMention(
                Map.of("extendedTextMessage", Map.of("contextInfo", Map.of(
                        "mentionedJid", List.of("another@s.whatsapp.net")))), "owner@s.whatsapp.net"));
        assertTrue(GroupNotificationEligibility.hasOwnerMention(
                Map.of("extendedTextMessage", Map.of("contextInfo", Map.of(
                        "mentionedJid", List.of("owner@s.whatsapp.net")))), "owner@s.whatsapp.net"));
    }

    @Test void onlyStructuralReplyMetadataProducesQuotedMessageId() {
        assertEquals("own-outgoing-1", GroupNotificationEligibility.quotedMessageId(
                Map.of("extendedTextMessage", Map.of("contextInfo", Map.of("stanzaId", "own-outgoing-1")))));
        assertNull(GroupNotificationEligibility.quotedMessageId(Map.of("conversation", "replied to you")));
    }

    @Test void oldGroupBacklogIsNotPresentedAsNewActivity() {
        assertFalse(GroupNotificationEligibility.isFresh(NOW.minus(Duration.ofHours(2)), NOW, Duration.ofHours(1)));
        assertTrue(GroupNotificationEligibility.isFresh(NOW.minus(Duration.ofMinutes(59)), NOW, Duration.ofHours(1)));
        assertFalse(GroupNotificationEligibility.isFresh(null, NOW, Duration.ofHours(1)));
    }
}

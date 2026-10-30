package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationInputMinimizerTest {
    @Test void keepsMostRecentMessageWhenInputExceedsLimit() {
        var input = new ClassificationInput(List.of("one", "two", "three", "four", "five", "six"));
        assertEquals(List.of("one", "two", "three", "four", "five", "six"), input.messages());
    }

    @Test void identifiesTargetSeparatelyFromFivePreviousMessages() {
        var input = new ClassificationInput(List.of("one", "two", "three", "four", "five", "six", "pode"));
        assertEquals("pode", input.target());
        assertEquals(List.of("two", "three", "four", "five", "six"), input.context());
    }

    @Test void unorderedBatchChoosesNewestEligibleTarget() {
        var newer = new dev.hugo.watcher.IncomingMessage(java.util.Optional.of("new"), "fixture@s.whatsapp.net", java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false, "notify", false, java.util.Map.of("conversation", "pode"), java.time.Instant.parse("2026-10-30T00:00:02Z"));
        var older = new dev.hugo.watcher.IncomingMessage(java.util.Optional.of("old"), "fixture@s.whatsapp.net", java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false, "notify", false, java.util.Map.of("conversation", "apaga"), java.time.Instant.parse("2026-10-30T00:00:01Z"));
        var batch = new dev.hugo.watcher.simulation.Batch(java.util.UUID.randomUUID(), "fixture@s.whatsapp.net", "", List.of(newer, older));
        assertEquals("pode", ClassificationInput.fromBatch(batch).target());
    }

    @Test void masksCommonContactAndIdentityTokensBeforeProviderInput() {
        String text = "Call +55 (11) 98765-4321 or 11987654321; email person.test@example.com; "
                + "WhatsApp 5511987654321@s.whatsapp.net; CPF 123.456.789-09; "
                + "CNPJ 12.345.678/0001-90; case ID 550e8400-e29b-41d4-a716-446655440000";

        String minimized = new ClassificationInput(List.of(text)).messages().getFirst();

        assertFalse(minimized.contains("+55 (11) 98765-4321"));
        assertFalse(minimized.contains("11987654321"));
        assertFalse(minimized.contains("person.test@example.com"));
        assertFalse(minimized.contains("5511987654321@s.whatsapp.net"));
        assertFalse(minimized.contains("123.456.789-09"));
        assertFalse(minimized.contains("12.345.678/0001-90"));
        assertFalse(minimized.contains("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(minimized.contains("[PHONE]"));
        assertTrue(minimized.contains("[EMAIL]"));
        assertTrue(minimized.contains("[JID]"));
        assertTrue(minimized.contains("[IDENTIFIER]"));
    }

    @Test void preservesOrdinaryAmountsDatesAndShortNumbers() {
        String text = "Budget R$ 1.234,56; due 27/10/2026; period 2026-10-27; ticket 12345";

        String minimized = new ClassificationInput(List.of(text)).messages().getFirst();

        assertEquals(text, minimized);
    }
}

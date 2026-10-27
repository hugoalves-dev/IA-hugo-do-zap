package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationInputMinimizerTest {
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

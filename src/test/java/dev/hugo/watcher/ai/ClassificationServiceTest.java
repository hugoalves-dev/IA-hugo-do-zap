package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.simulation.Batch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationServiceTest {
    @Test void authorizationDenialStopsBeforeFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClassificationProvider primary = new ClassificationProvider() {
            @Override public String name() { return "gemini"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                throw ClassificationProviderException.authorizationDenied("gemini");
            }
        };
        ClassificationProvider fallback = new ClassificationProvider() {
            @Override public String name() { return "openrouter"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                fallbackCalls.incrementAndGet();
                return ClassificationResult.classified(ClassificationResult.Intent.CONTACT_REQUEST,
                        true, ClassificationResult.Urgency.NORMAL, List.of(), 0.95);
            }
        };

        ClassificationResult result = new ClassificationService(primary, fallback).classify(batch("hello"));

        assertEquals(ClassificationResult.Status.UNCERTAIN, result.status());
        assertEquals(0, fallbackCalls.get());
    }

    @Test void technicalFailureUsesFallbackButPromptCannotCarryIdentifiers() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClassificationProvider primary = new ClassificationProvider() {
            @Override public String name() { return "gemini"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                assertEquals(List.of("hello"), input.messages());
                throw ClassificationProviderException.technical("gemini");
            }
        };
        ClassificationProvider fallback = new ClassificationProvider() {
            @Override public String name() { return "openrouter"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                fallbackCalls.incrementAndGet();
                return ClassificationResult.classified(ClassificationResult.Intent.CONTACT_REQUEST,
                        true, ClassificationResult.Urgency.NORMAL, List.of(), 0.95);
            }
        };

        ClassificationResult result = new ClassificationService(primary, fallback).classify(batch("hello"));

        assertEquals(1, fallbackCalls.get());
        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
    }

    @Test void uncertainProviderResultStaysUncertain() {
        ClassificationProvider provider = new ClassificationProvider() {
            @Override public String name() { return "gemini"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                return ClassificationResult.classified(ClassificationResult.Intent.CONTACT_REQUEST,
                        true, ClassificationResult.Urgency.NORMAL, List.of(), 0.2);
            }
        };

        assertEquals(ClassificationResult.Status.UNCERTAIN,
                new ClassificationService(provider, null).classify(batch("hello")).status());
    }

    @Test void invalidResponseDoesNotTriggerProviderFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClassificationProvider primary = new ClassificationProvider() {
            @Override public String name() { return "gemini"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
            }
        };
        ClassificationProvider fallback = new ClassificationProvider() {
            @Override public String name() { return "openrouter"; }
            @Override public ClassificationResult classify(ClassificationInput input) {
                fallbackCalls.incrementAndGet();
                return ClassificationResult.uncertain();
            }
        };

        assertEquals(ClassificationResult.Status.UNCERTAIN,
                new ClassificationService(primary, fallback).classify(batch("hello")).status());
        assertEquals(0, fallbackCalls.get());
    }

    private static Batch batch(String text) {
        var message = new IncomingMessage(Optional.of("secret-message-id"), "private-chat@s.whatsapp.net",
                Optional.of("person@s.whatsapp.net"), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("conversation", text), Instant.EPOCH);
        return new Batch(UUID.randomUUID(), "private-chat@s.whatsapp.net", "person@s.whatsapp.net", List.of(message));
    }
}

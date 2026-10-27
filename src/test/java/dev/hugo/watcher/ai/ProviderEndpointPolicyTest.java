package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ProviderEndpointPolicyTest {
    private static final ProviderAuthorization ALLOW = (provider, input) -> true;
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test void acceptsOnlyOfficialHttpsProviderHosts() {
        assertDoesNotThrow(() -> new GeminiProvider(URI.create("https://generativelanguage.googleapis.com/v1beta/models"),
                "model", "fake-key", new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        assertDoesNotThrow(() -> new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "model", "fake-key", new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
    }

    @Test void rejectsHttpEvenForLoopbackAndOfficialHostname() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiProvider(
                URI.create("http://generativelanguage.googleapis.com/v1beta/models"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(
                URI.create("http://127.0.0.1:9999/api/v1/chat/completions"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
    }

    @Test void rejectsNonProviderHttpsHost() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiProvider(
                URI.create("https://api.example.com/v1beta/models"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(
                URI.create("https://attacker.example/api/v1/chat/completions"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
    }

    @Test void preventsCredentialsFromCrossingBetweenOfficialProviderHosts() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiProvider(
                URI.create("https://openrouter.ai/api/v1/chat/completions"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(
                URI.create("https://generativelanguage.googleapis.com/v1beta/models"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
    }

    @Test void rejectsUserInfoQueryAndFragment() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiProvider(
                URI.create("https://user:pass@generativelanguage.googleapis.com/v1beta/models"), "model", "fake-key",
                new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        for (String uri : new String[]{
                "https://generativelanguage.googleapis.com/v1beta/models?key=secret",
                "https://generativelanguage.googleapis.com/v1beta/models#redirect",
                "https://user:pass@openrouter.ai/api/v1/chat/completions",
                "https://openrouter.ai/api/v1/chat/completions?token=secret",
                "https://openrouter.ai/api/v1/chat/completions#redirect"}) {
            assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(URI.create(uri), "model", "fake-key",
                    new FakeProviderTransport(request -> null), ALLOW, TIMEOUT));
        }
    }
}

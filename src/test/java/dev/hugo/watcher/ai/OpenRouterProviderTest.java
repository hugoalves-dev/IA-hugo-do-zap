package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterProviderTest {
    @Test void enforcesPrivateRoutingAndMinimizedPrompt() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
                """));

        var result = provider(transport, (name, input) -> true)
                .classify(new ClassificationInput(List.of("Contact +55 (11) 98765-4321; mail person.test@example.com")));

        String body = FakeProviderTransport.requestBody(transport.requests().getFirst());
        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
        assertTrue(body.contains("\"zdr\":true"));
        assertTrue(body.contains("\"data_collection\":\"deny\""));
        assertTrue(body.contains("\"allow_fallbacks\":false"));
        assertTrue(body.contains("untrusted"));
        assertFalse(body.contains("+55 (11) 98765-4321"));
        assertFalse(body.contains("person.test@example.com"));
    }

    @Test void unavailablePrivateRouteFailsClosedWithoutRetry() {
        var requests = new AtomicInteger();
        var transport = new FakeProviderTransport(request -> {
            requests.incrementAndGet();
            return FakeProviderTransport.response(404, "no eligible endpoints");
        });

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("private text"))));

        assertEquals(ClassificationProviderException.Kind.PRIVACY_ROUTE_UNAVAILABLE, error.kind());
        assertEquals(1, requests.get());
    }

    @Test void modelCannotReturnPermissionOrPersonalReferenceFields() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91,\\"authorized\\":true}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void inconsistentSchemaFieldsFailClosed() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"NONE\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void deniedAuthorizationMakesNoTransportCall() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, "{}"));

        assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> false)
                        .classify(new ClassificationInput(List.of("hello"))));
        assertEquals(0, transport.requests().size());
    }

    @Test void oversizedResponseFailsClosedBeforeParsing() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, "x".repeat(1024 * 1024)));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("hello"))));

        assertEquals("RESPONSE_TOO_LARGE", error.kind().name());
    }

    @Test void redirectResponseIsRejectedWithoutSecondRequest() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(302, "redirect"));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.CONFIGURATION_ERROR, error.kind());
        assertEquals(1, transport.requests().size());
    }

    private static OpenRouterProvider provider(ProviderTransport transport, ProviderAuthorization authorization) {
        return new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "test/model", "fake-key", transport, authorization, Duration.ofSeconds(2));
    }
}

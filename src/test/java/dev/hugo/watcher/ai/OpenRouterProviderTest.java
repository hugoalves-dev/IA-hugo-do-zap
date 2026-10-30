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
        assertFalse(body.contains("\"temperature\""));
        assertTrue(body.contains("\"require_parameters\":true"));
        assertTrue(body.contains("\"max_tokens\":300"));
        assertTrue(body.contains("untrusted"));
        assertFalse(body.contains("+55 (11) 98765-4321"));
        assertFalse(body.contains("person.test@example.com"));
    }


    /** Regression: Haiku 5.5 endpoints support strict JSON Schema and ZDR, but not temperature. */
    @Test void haiku55OmitsTemperatureWhileKeepingPrivateStructuredRouting() {
        var transport = new FakeProviderTransport(request -> {
            if (FakeProviderTransport.requestBody(request).contains("\"temperature\""))
                return FakeProviderTransport.response(404, "No endpoints found that can handle the requested parameters");
            return FakeProviderTransport.response(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"CONTACT_REQUEST\\\",\\\"requestDetected\\\":true,\\\"urgency\\\":\\\"NORMAL\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.91}\"}}]}");
        });
        var provider = new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "anthropic/claude-haiku-5.5", "fake-key", transport, (name, input) -> true,
                Duration.ofSeconds(2));

        var result = provider.classify(new ClassificationInput(List.of("Preciso conversar sobre um assunto importante.")));

        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
        assertEquals(1, transport.requests().size());
        String body = FakeProviderTransport.requestBody(transport.requests().getFirst());
        assertTrue(body.contains("\"model\":\"anthropic/claude-haiku-5.5\""));
        assertFalse(body.contains("\"temperature\""));
        assertTrue(body.contains("\"max_tokens\":300"));
        assertTrue(body.contains("\"zdr\":true"));
        assertTrue(body.contains("\"data_collection\":\"deny\""));
        assertTrue(body.contains("\"allow_fallbacks\":false"));
        assertTrue(body.contains("\"require_parameters\":true"));
        assertTrue(body.contains("\"response_format\""));
        assertTrue(body.contains("\"json_schema\""));
        assertTrue(body.contains("\"strict\":true"));
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

    @org.junit.jupiter.api.Test void urgencyRubricDistinguishesImportanceFromTimePressure() {
        String prompt = AbstractHttpClassificationProvider.SYSTEM_PROMPT;
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("important alone"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("today"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("immediate emergency"));
    }

    private static OpenRouterProvider provider(ProviderTransport transport, ProviderAuthorization authorization) {
        return new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "test/model", "fake-key", transport, authorization, Duration.ofSeconds(2));
    }
}

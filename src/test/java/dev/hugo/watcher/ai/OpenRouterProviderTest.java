package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterProviderTest {
    @Test void acceptsNotificationAndReplyRecommendationsSeparatelyFromUrgency() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"notificationRecommended\\":true,\\"replyRecommended\\":false,\\"urgency\\":\\"LOW\\",\\"evidence\\":[\\"EXPLICIT_REQUEST\\"],\\"confidence\\":0.91}"}}]}
                """));

        var result = provider(transport, (name, input) -> true)
                .classify(new ClassificationInput(List.of("Quando puder, me retorne.")));

        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
        assertTrue(result.notificationRecommended());
        assertFalse(result.replyRecommended());
        assertEquals(List.of(ClassificationResult.Evidence.EXPLICIT_REQUEST), result.evidence());
    }

    @Test void rejectsReplyRecommendationWithoutNotification() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"notificationRecommended\\":false,\\"replyRecommended\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.95}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("Preciso de ajuda."))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void rejectsReplyRecommendationWithoutDetectedRequest() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"NONE\\",\\"requestDetected\\":false,\\"notificationRecommended\\":true,\\"replyRecommended\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.95}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("Apenas uma atualização."))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void rejectsFreeTextAsClassificationEvidence() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"notificationRecommended\\":true,\\"replyRecommended\\":false,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[\\"555-0100 pediu retorno\\"],\\"confidence\\":0.95}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("Preciso de ajuda."))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void traceMetadataCarriesOnlyOpaqueBatchReference() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"notificationRecommended\\\":false,\\\"replyRecommended\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}"));
        var id = java.util.UUID.randomUUID();
        var message = new dev.hugo.watcher.IncomingMessage(java.util.Optional.of("private-message"), "private@s.whatsapp.net", java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false, "notify", false, java.util.Map.of("conversation", "Olá"), java.time.Instant.EPOCH);
        var input = ClassificationInput.fromBatch(new dev.hugo.watcher.simulation.Batch(id, "private@s.whatsapp.net", "", List.of(message)));
        provider(transport, (name, in) -> true).classify(input);
        String body = FakeProviderTransport.requestBody(transport.requests().getFirst());
        assertTrue(body.contains(id.toString()));
        assertFalse(body.contains("private@s.whatsapp.net"));
        assertFalse(body.contains("private-message"));
    }

    @Test void sendsTypedSenderEnvelopeWithoutRawParticipantOrGroupJids() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}"));
        var first = new dev.hugo.watcher.IncomingMessage(java.util.Optional.of("one"), "team@g.us",
                java.util.Optional.of("1111@lid"), java.util.Optional.empty(), java.util.Optional.empty(),
                false, "notify", false, java.util.Map.of("conversation", "primeira"), java.time.Instant.parse("2026-10-30T00:00:01Z"));
        var target = new dev.hugo.watcher.IncomingMessage(java.util.Optional.of("two"), "team@g.us",
                java.util.Optional.of("2222@lid"), java.util.Optional.empty(), java.util.Optional.empty(),
                false, "notify", false, java.util.Map.of("conversation", "pode"), java.time.Instant.parse("2026-10-30T00:00:02Z"));
        var batch = new dev.hugo.watcher.simulation.Batch(java.util.UUID.randomUUID(), "team@g.us", "2222@lid", List.of(first, target));
        var input = ClassificationInput.fromBatch(batch,
                jid -> new ClassificationInput.SenderIdentity(jid, "João & Café"));

        provider(transport, (name, in) -> true).classify(input);

        String body = FakeProviderTransport.requestBody(transport.requests().getFirst());
        assertTrue(body.contains("group_participant"));
        assertTrue(body.contains("João &amp; Café") || body.contains("João & Café"));
        assertTrue(body.contains("sender_1"));
        assertTrue(body.contains("sender_2"));
        assertFalse(body.contains("1111@lid"));
        assertFalse(body.contains("2222@lid"));
        assertFalse(body.contains("team@g.us"));
    }
    @Test void enforcesPrivateRoutingAndMinimizedPrompt() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"notificationRecommended\\":true,\\"replyRecommended\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
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
            return FakeProviderTransport.response(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"CONTACT_REQUEST\\\",\\\"requestDetected\\\":true,\\\"notificationRecommended\\\":true,\\\"replyRecommended\\\":true,\\\"urgency\\\":\\\"NORMAL\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.91}\"}}]}");
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
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"notificationRecommended\\":true,\\"replyRecommended\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91,\\"authorized\\":true}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(transport, (name, input) -> true)
                        .classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void inconsistentSchemaFieldsFailClosed() {
        var transport = new FakeProviderTransport(request -> FakeProviderTransport.response(200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"NONE\\",\\"requestDetected\\":true,\\"notificationRecommended\\":false,\\"replyRecommended\\":false,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
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


    @Test void reasoningIsExplicitAndTokenBudgetIsConfigurable() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"notificationRecommended\\\":false,\\\"replyRecommended\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}"));
        for (boolean reasoning : new boolean[]{false,true}) {
            var current = new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                    "anthropic/claude-haiku-5.5", "fake-key", http, (name, input) -> true,
                    Duration.ofSeconds(2), 512, reasoning);
            assertEquals(ClassificationResult.Status.CLASSIFIED,
                    current.classify(new ClassificationInput(List.of("Oi"))).status());
            String payload = FakeProviderTransport.requestBody(http.requests().getLast());
            assertTrue(payload.contains("\"max_tokens\":512"));
            assertTrue(payload.contains("\"reasoning\":{\"enabled\":" + reasoning + "}"));
            assertTrue(payload.contains("\"require_parameters\":true"));
            assertTrue(payload.contains("\"zdr\":true"));
            assertTrue(payload.contains("\"data_collection\":\"deny\""));
            assertTrue(payload.contains("\"allow_fallbacks\":false"));
        }
    }

    @Test void finishReasonLengthFailsClosedEvenWhenContentLooksValid() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}"));
        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(http, (name, input) -> true).classify(new ClassificationInput(List.of("Oi"))));
        assertEquals(ClassificationProviderException.Kind.OUTPUT_TRUNCATED, error.kind());
    }

    @Test void reasoningCanConsumeAllTokensWithoutAuthorizingDecision() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null,\"reasoning\":\"internal\"}}]}"));
        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(http, (name, input) -> true).classify(new ClassificationInput(List.of("Oi"))));
        assertEquals(ClassificationProviderException.Kind.OUTPUT_TRUNCATED, error.kind());
    }

    @Test void unsupportedFinishReasonsNeverAuthorizeClassification() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"{}\"}}]}"));
        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(http, (name, input) -> true).classify(new ClassificationInput(List.of("Oi"))));
        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void missingFinishReasonAndUsageWithValidJsonRemainsValidWithoutInventingTruncation() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"notificationRecommended\\\":false,\\\"replyRecommended\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}"));
        var result = provider(http, (name, input) -> true)
                .classify(new ClassificationInput(List.of("Oi")));
        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
    }

    @Test void missingFinishReasonAndUsageCannotAuthorizeAbsentContent() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"message\":{\"content\":null}}]}"));
        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(http, (name, input) -> true).classify(new ClassificationInput(List.of("Oi"))));
        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void anyTruncatedChoiceFailsClosedEvenIfFirstChoiceContainsValidJson() {
        var http = new FakeProviderTransport(request -> FakeProviderTransport.response(200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"urgency\\\":\\\"LOW\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}},{\"finish_reason\":\"length\",\"message\":{\"content\":null}}]}"));
        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(http, (name, input) -> true).classify(new ClassificationInput(List.of("Oi"))));
        assertEquals(ClassificationProviderException.Kind.OUTPUT_TRUNCATED, error.kind());
    }

    private static OpenRouterProvider provider(ProviderTransport transport, ProviderAuthorization authorization) {
        return new OpenRouterProvider(URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "test/model", "fake-key", transport, authorization, Duration.ofSeconds(2));
    }
}

package dev.hugo.watcher.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenRouterPromptGeneratorDiagnosticsTest {
    @Test void routing404LogsOnlySafeStructuredDiagnostics() {
        String response = """
                {"error":{"code":404,"type":"not_found_error",
                  "message":"Secret instructions: Authorization: Bearer should-never-appear",
                  "metadata":{
                    "failed_routing_step":"Filter by Allowed Providers",
                    "available_providers":["private-provider-name"],
                    "routing_funnel":[{"step":"secret-workflow","endpoint_count":0}]
                  }}}
                """;

        String log = rejectedResponseLog(404, response);

        assertTrue(log.contains("event=HTTP_REJECTED"), log);
        assertTrue(log.contains("operation=REFINE"), log);
        assertTrue(log.contains("model=anthropic/claude-haiku-5.5"), log);
        assertTrue(log.contains("httpStatus=404"), log);
        assertTrue(log.contains("maxTokens=2048"), log);
        assertTrue(log.contains("errorCode=404"), log);
        assertTrue(log.contains("errorType=not_found_error"), log);
        assertTrue(log.contains("failedRoutingStep=FILTER_BY_ALLOWED_PROVIDERS"), log);
        assertFalse(log.contains("should-never-appear"), log);
        assertFalse(log.contains("private-provider-name"), log);
        assertFalse(log.contains("secret-workflow"), log);
    }

    @Test void maliciousOrUnrecognizedFieldsAreNotCopiedIntoLogs() {
        String response = """
                {"error":{"code":"private-api-token","type":"unsafe\\nInjected log line",
                  "metadata":{"failed_routing_step":"Filter by user-sensitive-string"}}}
                """;

        String log = rejectedResponseLog(404, response);

        assertTrue(log.contains("httpStatus=404"), log);
        assertTrue(log.contains("errorCode=UNKNOWN"), log);
        assertTrue(log.contains("errorType=UNKNOWN"), log);
        assertTrue(log.contains("failedRoutingStep=UNKNOWN"), log);
        assertFalse(log.contains("private-api-token"), log);
        assertFalse(log.contains("Injected log line"), log);
        assertFalse(log.contains("user-sensitive-string"), log);
    }

    @Test void malformedBodyIsSafeAndCannotHideProviderFailure() {
        String log = rejectedResponseLog(503, "not JSON: Bearer secret-should-not-appear");

        assertTrue(log.contains("httpStatus=503"), log);
        assertTrue(log.contains("errorCode=UNKNOWN"), log);
        assertTrue(log.contains("errorType=UNKNOWN"), log);
        assertTrue(log.contains("failedRoutingStep=UNKNOWN"), log);
        assertFalse(log.contains("secret-should-not-appear"), log);
    }

    @Test void knownNonNumericErrorCodeAndRoutingStepRemainQueryable() {
        String response = """
                {"error":{"code":"no_endpoints_found","type":"routing_error",
                  "metadata":{"failed_routing_step":"Filter by Required Parameters"}}}
                """;

        String log = rejectedResponseLog(404, response);

        assertTrue(log.contains("errorCode=no_endpoints_found"), log);
        assertTrue(log.contains("errorType=routing_error"), log);
        assertTrue(log.contains("failedRoutingStep=FILTER_BY_REQUIRED_PARAMETERS"), log);
    }

    private static String rejectedResponseLog(int statusCode, String responseBody) {
        var budgets = mock(BudgetService.class);
        when(budgets.reserve(any(), any())).thenReturn(new Reservation(UUID.randomUUID(), 10,
                LocalDate.now(), LocalDate.now().withDayOfMonth(1)));
        var properties = new AiClassificationProperties(true, null, null, "fake-api-key",
                "anthropic/claude-haiku-5.5", 10, Duration.ofSeconds(3), null,
                true, false, true, false, 300, false);
        ProviderTransport fake = (request, timeout, maximumBytes) ->
                new ProviderTransport.Response(statusCode, responseBody);
        var generator = new OpenRouterPromptGenerator(properties, fake, budgets, Clock.systemUTC());

        var logger = (Logger) LoggerFactory.getLogger(OpenRouterPromptGenerator.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var failure = assertThrows(PromptGenerationFailure.class, () ->
                    generator.generate("Safe operational policy.", "Clarify urgent requests.",
                            () -> true, new PromptGenerationContext(
                                    UUID.fromString("50000000-0000-0000-0000-000000000050"), "REFINE")));
            assertEquals("PROVIDER/PROVIDER_FAILURE", failure.getMessage());
            assertNull(failure.getCause());
            verify(budgets).markUncertain(any());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
        var entries = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("event=HTTP_REJECTED"))
                .toList();
        assertEquals(1, entries.size(), entries.toString());
        return entries.getFirst();
    }
}

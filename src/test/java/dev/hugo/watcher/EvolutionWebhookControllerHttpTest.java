package dev.hugo.watcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class EvolutionWebhookControllerHttpTest {
    private static final String TOKEN = "http-test-token";
    private static final String EVENT = """
            {"event":"messages.upsert","instance":"watcher-http-test","data":{"key":{"id":"http-evt-1"}}}
            """;

    private EvolutionWebhookProperties properties;
    private InboxRepository inbox;
    private ConnectionService connections;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        properties = new EvolutionWebhookProperties();
        properties.setWebhookToken(TOKEN);
        properties.setInstance("watcher-http-test");
        properties.setMaxBodyBytes(1024);
        inbox = mock(InboxRepository.class);
        connections = mock(ConnectionService.class);
        http = MockMvcBuilders.standaloneSetup(new EvolutionWebhookController(inbox, properties, connections))
                .addFilters(new WebhookBodyLimitFilter(properties))
                .build();
    }

    @Test void rejectsUnauthorizedOriginWithoutCallingInbox() throws Exception {
        assertEquals(401, http.perform(post("/internal/evolution/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    @Test void rejectsUnauthorizedInstanceWithoutCallingInbox() throws Exception {
        String wrongInstance = EVENT.replace("watcher-http-test", "other-instance");
        assertEquals(403, send(wrongInstance).andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    @Test void rejectsMalformedJsonWithoutCallingInbox() throws Exception {
        assertEquals(400, send("{bad json").andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    @Test void rejectsOversizedBodyBeforeCallingInbox() throws Exception {
        String oversized = "{" + " ".repeat(1100) + "}";
        assertEquals(413, send(oversized).andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    @Test void returnsConflictSafeDuplicateResponse() throws Exception {
        when(inbox.accept(any(EvolutionEvent.class))).thenReturn(Acceptance.DUPLICATE);
        assertEquals(200, send(EVENT).andReturn().getResponse().getStatus());
        verify(inbox).accept(argThat(event -> event.source().equals("evolution")
                && event.instance().equals("watcher-http-test")
                && event.eventType().equals("messages.upsert")
                && event.externalId().equals("http-evt-1")));
    }

    @Test void reportsDatabaseFailureWithoutSuccessResponse() throws Exception {
        when(inbox.accept(any(EvolutionEvent.class))).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertEquals(503, send(EVENT).andReturn().getResponse().getStatus());
    }

    @Test void respondsAcceptedWhenInboxRepositoryAcceptsEvent() throws Exception {
        when(inbox.accept(any(EvolutionEvent.class))).thenReturn(Acceptance.NEW);
        assertEquals(202, send(EVENT).andReturn().getResponse().getStatus());
    }

    @Test void routesOfficialConnectionUpdateLoggedOutEventToManualPairingAlertEvenAsFirstEvent() throws Exception {
        String event = """
                {"event":"connection.update","instance":"watcher-http-test",
                 "data":{"instance":"watcher-http-test","state":"close","statusReason":401}}
                """;

        assertEquals(200, send(event).andReturn().getResponse().getStatus());

        verify(connections).onState(argThat(connectionEvent -> connectionEvent.state() == ConnectionEvent.State.MANUAL_PAIRING_REQUIRED
                && connectionEvent.statusCode() == 401 && "logged_out".equals(connectionEvent.reason())));
        verifyNoInteractions(inbox);
    }

    @Test void routesOfficialOpenConnectionUpdateToConnectedState() throws Exception {
        String event = """
                {"event":"connection.update","instance":"watcher-http-test",
                 "data":{"instance":"watcher-http-test","state":"open","statusReason":200}}
                """;

        assertEquals(200, send(event).andReturn().getResponse().getStatus());

        verify(connections).onState(argThat(connectionEvent -> connectionEvent.state() == ConnectionEvent.State.CONNECTED
                && connectionEvent.statusCode() == 200));
        verifyNoInteractions(inbox);
    }

    @Test void returnsRetryableServiceUnavailableWhenConnectionStateCannotBePersisted() throws Exception {
        String event = """
                {"event":"connection.update","instance":"watcher-http-test",
                 "data":{"instance":"watcher-http-test","state":"close","statusReason":408}}
                """;
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(connections).onState(any(ConnectionEvent.class));

        assertEquals(503, send(event).andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    @Test void leavesUnmappedRoutesUnavailable() throws Exception {
        assertEquals(404, http.perform(post("/internal/other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(inbox);
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return http.perform(post("/internal/evolution/webhook")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}

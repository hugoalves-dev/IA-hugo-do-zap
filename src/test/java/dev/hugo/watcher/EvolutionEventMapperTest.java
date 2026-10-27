package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionEventMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final EvolutionEventMapper mapper = new EvolutionEventMapper();

    @Test void mapsOnlyAnEventWithAStableMessageId() throws Exception {
        final tools.jackson.databind.JsonNode payload;
        try (var fixture = getClass().getResourceAsStream("/fixtures/evolution/messages-upsert-synthetic.json")) {
            assertNotNull(fixture);
            payload = json.readTree(fixture);
        }

        var events = mapper.map(payload, "webhook");

        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals("evolution", event.source());
        assertEquals("webhook", event.distribution());
        assertEquals("messages.upsert", event.eventType());
        assertEquals("synthetic-message-1", event.externalId());
        assertEquals(Instant.ofEpochSecond(1_700_000_000), event.timestamp().orElseThrow());
        assertEquals(Optional.of("5511999999999@s.whatsapp.net"), event.jid());
        assertTrue(event.archived().isEmpty());
    }

    @Test void missingNullAndExplicitArchiveValuesStayTriState() throws Exception {
        var missing = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{"key":{"id":"m-1"}}}
                """);
        var explicitNull = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{"key":{"id":"m-1"},"archived":null}}
                """);
        var archived = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{"key":{"id":"m-1"},"archived":true}}
                """);
        var unarchived = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{"key":{"id":"m-1"},"archived":false}}
                """);

        assertTrue(mapper.map(missing, "webhook").getFirst().archived().isEmpty());
        assertTrue(mapper.map(explicitNull, "webhook").getFirst().archived().isEmpty());
        assertEquals(Optional.of(true), mapper.map(archived, "webhook").getFirst().archived());
        assertEquals(Optional.of(false), mapper.map(unarchived, "webhook").getFirst().archived());
    }

    @Test void doesNotMapAnEventWithoutStableId() throws Exception {
        var payload = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{
                  "key":{"remoteJid":"5511999999999@s.whatsapp.net"}}}
                """);

        assertTrue(mapper.map(payload, "webhook").isEmpty());

        var unverifiedArchive = json.readTree("""
                {"event":"chats.update","instance":"synthetic","data":{
                  "remoteJid":"120363000000000000@g.us","archived":true}}
                """);
        assertTrue(mapper.map(unverifiedArchive, "webhook").isEmpty());
    }

    @Test void keepsDifferentGroupParticipantsAsDifferentSenders() throws Exception {
        var first = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{
                  "key":{"id":"m-1","remoteJid":"120363000000000000@g.us","participant":"1001@lid","participantAlt":"5511999999999@s.whatsapp.net"}}}
                """);
        var second = json.readTree("""
                {"event":"messages.upsert","instance":"synthetic","data":{
                  "key":{"id":"m-2","remoteJid":"120363000000000000@g.us","participant":"1002@lid"}}}
                """);

        assertNotEquals(mapper.map(first, "webhook").getFirst().senderJid(),
                mapper.map(second, "webhook").getFirst().senderJid());
        assertEquals(java.util.Set.of("5511999999999@s.whatsapp.net"),
                mapper.map(first, "webhook").getFirst().aliases());
        assertEquals(Optional.of(IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR),
                mapper.map(first, "webhook").getFirst().aliasEvidence());
        assertTrue(mapper.map(second, "webhook").getFirst().aliases().isEmpty());
    }
}

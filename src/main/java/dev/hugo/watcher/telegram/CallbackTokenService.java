package dev.hugo.watcher.telegram;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Validates short-lived callback confirmations; database operations live in a repository. */
@Service
public class CallbackTokenService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CallbackTokenLedgerRepository ledger;
    private final Clock clock;

    public CallbackTokenService(CallbackTokenLedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public String issue(long userId, long chatId, CallbackAction action, Instant expiresAt) {
        Instant now = clock.instant();
        if (userId <= 0 || chatId <= 0 || expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plusSeconds(600)))
            throw new IllegalArgumentException("invalid callback token binding or expiry");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String actionJson = writeAction(action);
        ledger.purgeExpiredOrConsumed(now);
        ledger.issue(hash(token), userId, chatId, actionJson, now, expiresAt);
        return token;
    }

    public CallbackAction consume(String token, long userId, long chatId) {
        if (token == null || token.isBlank() || userId <= 0 || chatId <= 0) throw new CallbackTokenRejectedException();
        var actions = ledger.consume(hash(token), userId, chatId, clock.instant());
        if (actions.isEmpty()) throw new CallbackTokenRejectedException();
        return parseAction(actions.getFirst());
    }

    /** Revoke the paired confirmation token on Cancel within the caller's transaction. */
    public boolean invalidate(String token, long userId, long chatId) {
        if (token == null || token.isBlank() || userId <= 0 || chatId <= 0) return false;
        return ledger.invalidate(hash(token), userId, chatId, clock.instant());
    }

    private static String writeAction(CallbackAction action) {
        try { return JSON.writeValueAsString(Map.of("command", action.command(), "arguments", action.arguments())); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("callback action cannot be encoded", ex); }
    }

    private static CallbackAction parseAction(String value) {
        try {
            Map<?, ?> action = JSON.readValue(value, Map.class);
            Object rawArguments = action.get("arguments");
            List<String> arguments = rawArguments instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
            return new CallbackAction(String.valueOf(action.get("command")), arguments);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("stored callback action is invalid", ex);
        }
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
}

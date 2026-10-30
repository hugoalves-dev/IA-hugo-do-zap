package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class CallbackTokenService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CallbackTokenService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
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
        jdbc.update("delete from telegram_admin_callback_tokens where expires_at<=? or consumed_at is not null", Timestamp.from(now));
        jdbc.update("""
                insert into telegram_admin_callback_tokens(token_hash,user_id,chat_id,action,created_at,expires_at)
                values (?,?,?,?::jsonb,?,?)
                """, hash(token), userId, chatId, actionJson, Timestamp.from(now), Timestamp.from(expiresAt));
        return token;
    }

    public CallbackAction consume(String token, long userId, long chatId) {
        if (token == null || token.isBlank() || userId <= 0 || chatId <= 0) throw new CallbackTokenRejectedException();
        List<String> actions = jdbc.query("""
                update telegram_admin_callback_tokens
                   set consumed_at=?
                 where token_hash=? and user_id=? and chat_id=? and consumed_at is null and expires_at>?
                returning action::text
                """, (rs, row) -> rs.getString(1), Timestamp.from(clock.instant()), hash(token), userId, chatId,
                Timestamp.from(clock.instant()));
        if (actions.isEmpty()) throw new CallbackTokenRejectedException();
        return parseAction(actions.getFirst());
    }

    /** Revoke the paired confirmation token on Cancel within the caller's transaction. */
    public boolean invalidate(String token, long userId, long chatId) {
        if (token == null || token.isBlank() || userId <= 0 || chatId <= 0) return false;
        return jdbc.update("""
                update telegram_admin_callback_tokens set consumed_at=?
                where token_hash=? and user_id=? and chat_id=? and consumed_at is null
                """, Timestamp.from(clock.instant()), hash(token), userId, chatId) == 1;
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

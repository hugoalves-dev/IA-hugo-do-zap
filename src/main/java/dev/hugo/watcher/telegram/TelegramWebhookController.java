package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Acknowledge only after durable storage; execution is delegated to a separate worker. */
@RestController
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "webhook-enabled", havingValue = "true")
public class TelegramWebhookController {
    public static final String PATH = "/api/webhooks/telegram";
    private final byte[] secret;
    private final JdbcTemplate jdbc;

    public TelegramWebhookController(TelegramAdminProperties properties, JdbcTemplate jdbc) {
        this.secret = properties.getWebhookSecret().getBytes(StandardCharsets.US_ASCII);
        this.jdbc = jdbc;
    }

    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String supplied,
            @RequestBody String body) {
        if (supplied == null || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.US_ASCII)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        final Update update;
        try {
            update = BotUtils.parseUpdate(body);
            if (update == null || update.updateId() == null || update.updateId() < 0)
                return ResponseEntity.badRequest().build();
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().build();
        }
        try {
            int inserted = jdbc.update("""
                    INSERT INTO telegram_webhook_inbox(update_id,payload)
                    VALUES (?,?::jsonb) ON CONFLICT (update_id) DO NOTHING
                    """, update.updateId().longValue(), body);
            return ResponseEntity.status(inserted == 1 ? HttpStatus.ACCEPTED : HttpStatus.OK).build();
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}

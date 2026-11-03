package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import dev.hugo.watcher.RabbitEventPublisher;

/** ACK only after durable storage; downstream execution is performed by a separate worker. */
@RestController
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "webhook-enabled", havingValue = "true")
public class TelegramWebhookController {
    public static final String PATH = "/api/webhooks/telegram";
    private final byte[] secret;
    private final TelegramWebhookDataRepository inbox;
    private final RabbitEventPublisher publisher;

    @Autowired
    public TelegramWebhookController(TelegramAdminProperties properties, TelegramWebhookDataRepository inbox,
                                     ObjectProvider<RabbitEventPublisher> publisher) {
        this(properties, inbox, publisher.getIfAvailable());
    }

    public TelegramWebhookController(TelegramAdminProperties properties, TelegramWebhookDataRepository inbox,
                                     RabbitEventPublisher publisher) {
        this.secret = properties.getWebhookSecret().getBytes(StandardCharsets.US_ASCII);
        this.inbox = inbox;
        this.publisher = publisher;
    }

    public TelegramWebhookController(TelegramAdminProperties properties, TelegramWebhookDataRepository inbox) {
        this(properties, inbox, (RabbitEventPublisher) null);
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
            if (publisher != null) {
                publisher.publish("admin", update.updateId().toString(), body);
                return ResponseEntity.accepted().build();
            }
            int inserted = inbox.insertIfAbsent(update.updateId().longValue(), body);
            return ResponseEntity.status(inserted == 1 ? HttpStatus.ACCEPTED : HttpStatus.OK).build();
        } catch (DataAccessException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}

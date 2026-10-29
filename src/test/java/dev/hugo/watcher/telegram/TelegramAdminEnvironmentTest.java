package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.boot.context.properties.bind.Binder;
import java.util.Map;
import jakarta.validation.Validation;
import static org.junit.jupiter.api.Assertions.*;

class TelegramAdminEnvironmentTest {
    @Test void pollingRequiresPositiveUserIdButDisabledPollingAllowsMissingId() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var admin = new TelegramAdminProperties();
        assertTrue(validator.validate(admin).isEmpty());
        admin.setPollingEnabled(true);
        assertFalse(validator.validate(admin).isEmpty());
        admin.setUserId(0L);
        assertFalse(validator.validate(admin).isEmpty());
        admin.setUserId(12345L);
        assertTrue(validator.validate(admin).isEmpty());
    }

    @Test void operatorEnvironmentEnablesPollingAndBindsAdminIdentity() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("operator", Map.of(
                "WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED", "true",
                "WATCHER_TELEGRAM_ADMIN_USER_ID", "12345")));
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        var admin = Binder.get(environment).bind("watcher.telegram.admin", TelegramAdminProperties.class).orElseThrow(IllegalStateException::new);
        assertTrue(admin.isPollingEnabled());
        assertEquals(12345L, admin.getUserId());
    }
}


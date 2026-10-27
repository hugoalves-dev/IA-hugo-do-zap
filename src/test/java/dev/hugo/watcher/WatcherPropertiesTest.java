package dev.hugo.watcher;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WatcherPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void requiresTelegramBotTokenAndPrivateChatId() {
        var properties = new WatcherProperties();
        assertTrue(validator.validate(properties).stream().anyMatch(v -> v.getPropertyPath().toString().equals("telegramBotToken")));
        assertTrue(validator.validate(properties).stream().anyMatch(v -> v.getPropertyPath().toString().equals("telegramChatId")));
    }

    @Test void rejectsNonPositiveTelegramChatIds() {
        var properties = validProperties();
        properties.setTelegramChatId(0L);
        assertTrue(validator.validate(properties).stream().anyMatch(v -> v.getPropertyPath().toString().equals("telegramChatId")));
    }

    @Test void constrainsNotificationDaysToWeekdaysOneThroughSeven() {
        var properties = validProperties();
        properties.setNotificationDays(Set.of(0));
        assertFalse(validator.validate(properties).isEmpty());
        properties.setNotificationDays(Set.of(1, 7));
        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test void rejectsEmptyOrReversedNotificationWindows() {
        var properties = validProperties();
        properties.setNotificationWindows(List.of(new WatcherProperties.TimeWindow(LocalTime.NOON, LocalTime.NOON)));
        assertFalse(validator.validate(properties).isEmpty());
        properties.setNotificationWindows(List.of(new WatcherProperties.TimeWindow(LocalTime.of(8, 0), LocalTime.NOON)));
        assertTrue(validator.validate(properties).isEmpty());
    }

    private static WatcherProperties validProperties() {
        var properties = new WatcherProperties();
        properties.setTelegramBotToken("synthetic-test-token");
        properties.setTelegramChatId(123456789L);
        properties.setTimezone("America/Sao_Paulo");
        properties.setNotificationDays(Set.of(1, 2, 3, 4, 5));
        properties.setNotificationWindows(List.of(new WatcherProperties.TimeWindow(LocalTime.of(8, 0), LocalTime.NOON)));
        properties.setGroupJids(List.of("120363000000000000@g.us"));
        properties.setAllowedNameRegex(".*");
        properties.setMaxBatchSize(100);
        properties.setRetentionDays(7);
        return properties;
    }
}

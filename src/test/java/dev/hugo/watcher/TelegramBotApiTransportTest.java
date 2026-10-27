package dev.hugo.watcher;

import com.pengrad.telegrambot.model.ResponseParameters;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramBotApiTransportTest {
    @Test void propagatesTelegram429RetryAfterWithoutClaimingDelivery() {
        ResponseParameters parameters = mock(ResponseParameters.class);
        when(parameters.retryAfter()).thenReturn(30);
        SendResponse response = failedResponse(429, parameters);
        TelegramBotApiTransport transport = new TelegramBotApiTransport(123456789L, (chatId, text) -> response);

        TelegramDeliveryResult result = transport.send("fake request");

        assertFalse(result.delivered());
        assertEquals(Duration.ofSeconds(30), result.retryAfter());
    }

    @Test void leavesRetryAfterEmptyWhenTelegramDoesNotProvideIt() {
        TelegramBotApiTransport transport = new TelegramBotApiTransport(123456789L,
                (chatId, text) -> failedResponse(429, null));

        TelegramDeliveryResult result = transport.send("fake request");

        assertFalse(result.delivered());
        assertEquals(null, result.retryAfter());
    }

    private static SendResponse failedResponse(int code, ResponseParameters parameters) {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(false);
        when(response.errorCode()).thenReturn(code);
        when(response.parameters()).thenReturn(parameters);
        return response;
    }
}

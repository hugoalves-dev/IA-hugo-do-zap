package dev.hugo.watcher;

import com.pengrad.telegrambot.model.ResponseParameters;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramBotApiTransportTest {
    @Test void serverFailureDoesNotProveTheMessageWasRejected() {
        var transport=new TelegramBotApiTransport(123L,(chat,text)->failedResponse(500,null));
        assertNotEquals(TelegramDeliveryResult.failure(),transport.send("test"));
    }
    @Test void transportExceptionIsAmbiguousRatherThanConfirmedRejection() {
        var transport=new TelegramBotApiTransport(123L,(chat,text)->{throw new RuntimeException("fake timeout");});
        assertNotEquals(TelegramDeliveryResult.failure(),transport.send("test"));
    }
    @Test void absentResponseIsAmbiguousRatherThanConfirmedRejection() {
        var transport=new TelegramBotApiTransport(123L,(chat,text)->null);
        assertNotEquals(TelegramDeliveryResult.failure(),transport.send("test"));
    }
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
        assertTrue(result.rateLimited());
    }

    private static SendResponse failedResponse(int code, ResponseParameters parameters) {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(false);
        when(response.errorCode()).thenReturn(code);
        when(response.parameters()).thenReturn(parameters);
        return response;
    }
}

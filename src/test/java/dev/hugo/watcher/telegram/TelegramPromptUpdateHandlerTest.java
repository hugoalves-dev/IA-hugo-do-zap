package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramPromptUpdateHandlerTest {
    @Test void promptTextReachesOnlyTheConfiguredPrivateAdmin() {
        var commands = mock(AdminCommandService.class);
        var tokens = mock(CallbackTokenService.class);
        when(commands.acceptPromptInput("part", 1001, 2002, null)).thenReturn(AdminResult.accepted("ok"));
        var handler = new TelegramUpdateHandler(1001, 2002, commands, tokens);
        assertFalse(handler.handle(message("part", 9999, 2002, "private")).accepted());
        assertFalse(handler.handle(message("part", 1001, 9999, "private")).accepted());
        assertFalse(handler.handle(message("part", 1001, 2002, "group")).accepted());
        assertTrue(handler.handle(message("part", 1001, 2002, "private")).accepted());
        verify(commands, times(1)).acceptPromptInput("part", 1001, 2002, null);
        verifyNoMoreInteractions(commands);
        verifyNoInteractions(tokens);
    }

    @Test void replayedPromptCallbackNeverExecutesTwice() {
        var commands = mock(AdminCommandService.class);
        var tokens = mock(CallbackTokenService.class);
        when(tokens.consume("opaque", 1001, 2002)).thenReturn(new CallbackAction("prompt_publish", List.of("id", "4", "0")))
                .thenThrow(new CallbackTokenRejectedException());
        when(commands.execute(any())).thenReturn(AdminResult.accepted("ok"));
        var handler = new TelegramUpdateHandler(1001, 2002, commands, tokens);
        Update callback = callback();
        assertTrue(handler.handle(callback).accepted());
        assertFalse(handler.handle(callback).accepted());
        verify(commands, times(1)).execute(argThat(command -> command.name().equals("prompt_publish") && command.confirmed()));
    }

    private static Update message(String text, long userId, long chatId, String kind) {
        var update = mock(Update.class); var message = mock(Message.class); var user = mock(User.class); var chat = mock(Chat.class);
        when(update.message()).thenReturn(message); when(message.from()).thenReturn(user); when(user.id()).thenReturn(userId);
        when(message.chat()).thenReturn(chat); when(chat.id()).thenReturn(chatId); when(message.text()).thenReturn(text);
        when(chat.type()).thenReturn(Arrays.stream(Chat.Type.values()).filter(type -> type.name().equalsIgnoreCase(kind)
                || type.name().equalsIgnoreCase(kind + "_")).findFirst().orElseThrow());
        return update;
    }

    private static Update callback() {
        var update = mock(Update.class); var callback = mock(CallbackQuery.class); var message = mock(Message.class);
        var user = mock(User.class); var chat = mock(Chat.class);
        when(update.callbackQuery()).thenReturn(callback); when(callback.from()).thenReturn(user); when(user.id()).thenReturn(1001L);
        when(callback.data()).thenReturn("opaque"); when(callback.message()).thenReturn(message); when(message.chat()).thenReturn(chat);
        when(chat.id()).thenReturn(2002L); when(chat.type()).thenReturn(Arrays.stream(Chat.Type.values())
                .filter(type -> type.name().equalsIgnoreCase("private") || type.name().equalsIgnoreCase("private_"))
                .findFirst().orElseThrow());
        return update;
    }
}

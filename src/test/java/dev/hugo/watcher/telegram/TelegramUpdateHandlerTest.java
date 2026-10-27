package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramUpdateHandlerTest {
    @Test void rejectsWrongUserWrongChatAndGroupBeforeExecutingCommands() {
        AdminCommandService commands = mock(AdminCommandService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, commands, mock(CallbackTokenService.class));

        assertFalse(handler.handle(messageUpdate("/status", 9999, 2002, "private")).accepted());
        assertFalse(handler.handle(messageUpdate("/status", 1001, 9999, "private")).accepted());
        assertFalse(handler.handle(messageUpdate("/emergencia", 1001, 2002, "group")).accepted());
        verifyNoInteractions(commands);
    }

    @Test void acceptsConfiguredPrivateAdminCommand() {
        AdminCommandService commands = mock(AdminCommandService.class);
        when(commands.execute(any())).thenReturn(AdminResult.accepted("ok"));
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, commands, mock(CallbackTokenService.class));

        AdminResult result = handler.handle(messageUpdate("/status", 1001, 2002, "private"));

        assertTrue(result.accepted());
        verify(commands).execute(argThat((AdminCommand command) -> command.name().equals("status") && command.userId() == 1001 && command.chatId() == 2002));
    }

    @Test void callbackMustMatchPrivateAdminIdentityBeforeTokenConsumption() {
        CallbackTokenService tokens = mock(CallbackTokenService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, mock(AdminCommandService.class), tokens);
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        User user = mock(User.class);
        Message message = mock(Message.class);
        Chat chat = mock(Chat.class);
        when(update.callbackQuery()).thenReturn(callback);
        when(callback.from()).thenReturn(user);
        when(callback.message()).thenReturn(message);
        when(callback.data()).thenReturn("opaque-token");
        when(user.id()).thenReturn(1001L);
        when(message.chat()).thenReturn(chat);
        when(chat.id()).thenReturn(2002L);
        when(chat.type()).thenReturn(chatType("group"));

        assertFalse(handler.handle(update).accepted());
        verifyNoInteractions(tokens);
    }

    @Test void rejectsCallbacksFromWrongAdminUserOrPrivateChat() {
        CallbackTokenService tokens = mock(CallbackTokenService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, mock(AdminCommandService.class), tokens);

        assertFalse(handler.handle(callbackUpdate(9999, 2002, chatType("private"))).accepted());
        assertFalse(handler.handle(callbackUpdate(1001, 9999, chatType("private"))).accepted());
        verifyNoInteractions(tokens);
    }

    @Test void consumesCallbackAndExecutesOnlyItsBoundCommand() {
        CallbackTokenService tokens = mock(CallbackTokenService.class);
        AdminCommandService commands = mock(AdminCommandService.class);
        when(tokens.consume("opaque-token", 1001, 2002)).thenReturn(new CallbackAction("ocupado", List.of("30")));
        when(commands.execute(any())).thenReturn(AdminResult.accepted("ok"));
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, commands, tokens);

        assertTrue(handler.handle(callbackUpdate(1001, 2002, chatType("private"))).accepted());
        verify(commands).execute(argThat((AdminCommand command) -> command.name().equals("ocupado")
                && command.arguments().equals(List.of("30")) && command.confirmed()));
    }

    @Test void confirmationMessageConsumesTokenAndMarksCommandConfirmed() {
        CallbackTokenService tokens = mock(CallbackTokenService.class);
        AdminCommandService commands = mock(AdminCommandService.class);
        when(tokens.consume("opaque-token", 1001, 2002)).thenReturn(new CallbackAction("ferias", List.of("2026-12-01", "2026-12-02")));
        when(commands.execute(any())).thenReturn(AdminResult.accepted("ok"));
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, commands, tokens);

        assertTrue(handler.handle(messageUpdate("/confirm opaque-token", 1001, 2002, "private")).accepted());

        verify(commands).execute(argThat((AdminCommand command) -> command.name().equals("ferias")
                && command.arguments().equals(List.of("2026-12-01", "2026-12-02")) && command.confirmed()));
    }

    @Test void malformedSlashCommandDoesNotEscapeHandler() {
        AdminCommandService commands = mock(AdminCommandService.class);
        when(commands.execute(any())).thenReturn(AdminResult.rejected("invalid"));
        TelegramUpdateHandler handler = new TelegramUpdateHandler(1001, 2002, commands, mock(CallbackTokenService.class));

        assertFalse(handler.handle(messageUpdate("/", 1001, 2002, "private")).accepted());
        verify(commands).execute(argThat((AdminCommand command) -> command.name().equals("unknown")));
    }

    private Update messageUpdate(String text, long userId, long chatId, String chatType) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        Chat chat = mock(Chat.class);
        when(update.message()).thenReturn(message);
        when(message.text()).thenReturn(text);
        when(message.from()).thenReturn(user);
        when(user.id()).thenReturn(userId);
        when(message.chat()).thenReturn(chat);
        when(chat.id()).thenReturn(chatId);
        when(chat.type()).thenReturn(chatType(chatType));
        return update;
    }

    private Update callbackUpdate(long userId, long chatId, Chat.Type chatType) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        User user = mock(User.class);
        Message message = mock(Message.class);
        Chat chat = mock(Chat.class);
        when(update.callbackQuery()).thenReturn(callback);
        when(callback.from()).thenReturn(user);
        when(callback.message()).thenReturn(message);
        when(callback.data()).thenReturn("opaque-token");
        when(user.id()).thenReturn(userId);
        when(message.chat()).thenReturn(chat);
        when(chat.id()).thenReturn(chatId);
        when(chat.type()).thenReturn(chatType);
        return update;
    }

    private Chat.Type chatType(String name) {
        return java.util.Arrays.stream(Chat.Type.values()).filter(value -> value.name().equalsIgnoreCase(name)
                || value.name().equalsIgnoreCase(name + "_")).findFirst().orElseThrow();
    }
}

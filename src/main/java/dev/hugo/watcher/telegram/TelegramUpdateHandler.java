package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import dev.hugo.watcher.WatcherProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class TelegramUpdateHandler {
    private final long adminUserId;
    private final long privateChatId;
    private final AdminCommandService commands;
    private final CallbackTokenService tokens;

    @Autowired
    public TelegramUpdateHandler(TelegramAdminProperties admin, WatcherProperties watcher,
                                 AdminCommandService commands, CallbackTokenService tokens) {
        this(admin.getUserId() == null ? 0 : admin.getUserId(), watcher.getTelegramChatId() == null ? 0 : watcher.getTelegramChatId(), commands, tokens);
    }

    TelegramUpdateHandler(long adminUserId, long privateChatId, AdminCommandService commands, CallbackTokenService tokens) {
        this.adminUserId = adminUserId;
        this.privateChatId = privateChatId;
        this.commands = commands;
        this.tokens = tokens;
    }

    public AdminResult handle(Update update) {
        if (update == null) return AdminResult.rejected("Update inválido.");
        CallbackQuery callback = update.callbackQuery();
        if (callback != null) return handleCallback(callback);
        Message message = update.message();
        if (message == null) return AdminResult.rejected("Update não suportado.");
        User sender = message.from();
        Chat chat = message.chat();
        if (!authorized(sender, chat)) return AdminResult.rejected("Acesso negado.");
        String text = message.text();
        if (text == null || text.isBlank() || !text.stripLeading().startsWith("/"))
            return queueUnknown();
        Parsed parsed = parse(text);
        if (parsed.name().equals("confirm")) return confirm(parsed.arguments(), sender.id(), chat.id());
        return commands.execute(new AdminCommand(parsed.name(), parsed.arguments(), sender.id(), chat.id(), false));
    }

    private AdminResult handleCallback(CallbackQuery callback) {
        User sender = callback.from();
        Message message = callback.message();
        Chat chat = message == null ? null : message.chat();
        if (!authorized(sender, chat)) return AdminResult.rejected("Acesso negado.");
        try {
            CallbackAction action = tokens.consume(callback.data(), sender.id(), chat.id());
            return commands.execute(new AdminCommand(action.command(), action.arguments(), sender.id(), chat.id(), true));
        } catch (CallbackTokenRejectedException ex) {
            return AdminResult.rejected("Confirmação inválida, expirada ou já usada.");
        }
    }

    private AdminResult confirm(List<String> arguments, long userId, long chatId) {
        if (arguments.size() != 1) return AdminResult.rejected("Use /confirm <token>.");
        try {
            CallbackAction action = tokens.consume(arguments.getFirst(), userId, chatId);
            return commands.execute(new AdminCommand(action.command(), action.arguments(), userId, chatId, true));
        } catch (CallbackTokenRejectedException ex) {
            return AdminResult.rejected("Confirmação inválida, expirada ou já usada.");
        }
    }

    private boolean authorized(User sender, Chat chat) {
        return sender != null && chat != null && adminUserId > 0 && privateChatId > 0
                && sender.id() != null && sender.id() == adminUserId
                && chat.id() == privateChatId && isPrivate(chat.type());
    }

    private static boolean isPrivate(Chat.Type type) {
        return type != null && (type.name().equalsIgnoreCase("private") || type.name().equalsIgnoreCase("private_"));
    }

    private AdminResult queueUnknown() {
        return commands.execute(new AdminCommand("unknown", List.of(), adminUserId, privateChatId, false));
    }

    private static Parsed parse(String text) {
        String[] words = text.strip().split("\\s+");
        String name = words[0].substring(1).split("@", 2)[0].toLowerCase(java.util.Locale.ROOT);
        List<String> args = words.length > 1 ? Arrays.asList(words).subList(1, words.length) : List.of();
        if (!name.matches("[a-z0-9_]{1,32}")) return new Parsed("unknown", List.of());
        return new Parsed(name, List.copyOf(args));
    }

    private record Parsed(String name, List<String> arguments) { }
}

package dev.hugo.watcher.telegram;

public final class RuntimeSettingVersionConflictException extends IllegalArgumentException {
    public RuntimeSettingVersionConflictException() { super("A configuração mudou; atualize a tela antes de tentar novamente."); }
}

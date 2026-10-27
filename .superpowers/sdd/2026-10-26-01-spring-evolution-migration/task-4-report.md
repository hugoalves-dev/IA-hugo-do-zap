# Tarefa 4 — Estado de chats e seleção de mensagens

## Implementação

- `ChatStateService.apply` aceita apenas estado de archive explícito, origem declarada verificada, timestamp ordenável e ID de evento fornecido. Um bootstrap também exige snapshot sincronizado. `Optional.empty()` não cria um chat e fica em quarentena; update mais antigo ou empatado não reverte o snapshot. Desarquivar e sinais de leitura removem a pendência na mesma transação.
- `MessageService.accept` só processa mensagens `notify` com conteúdo de usuário, ID estável, chat sincronizado e `archived=true`. Histórico, conteúdo próprio, status, protocolos e grupos desativados são ignorados. IDs de mensagem são deduplicados atomicamente por hash do chat canônico, ID upstream e participante canônico; pares de alias só são unidos com evidência explícita.
- A configuração Java agora preserva regex de nome configurável, grupos ligados por padrão e nomes de grupo bloqueados por padrão (`233/234`). Preview replica normalização/truncamento de 140 code points e fallbacks de mídia. Pendências vencem em 1–7 dias; quarentena guarda apenas `{}` redigido, ID upstream real quando existe e expiração limitada a sete dias. O scheduler remove pendências e quarentena vencidas, além de hashes de deduplicação com mais de sete dias.
- A migração V4 adiciona os campos de snapshot/sincronização, expiração de pendências e tabela/indexes de quarentena.

## TDD e verificação

- **RED:** `mvn -Dtest=ChatStateServiceTest,MessageServiceTest,NotificationPolicyTest test` falhou em `testCompile` porque os serviços/DTOs e propriedades ainda não existiam. O teste isolado de purga também falhou em `testCompile` quando `SensitiveDataRetentionService` ainda não existia.
- **GREEN:** o mesmo comando, com Testcontainers configurado por `api.version=1.44`, `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` e `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, terminou com **15 testes, 0 falhas, 0 erros**. PostgreSQL 17 real aplicou as quatro migrações.
- `git diff --check`: sem erros.
- O build imprimiu avisos de depreciação `sun.misc.Unsafe` emitidos pelo Guice/Maven e de ciclo de vida interno do Testcontainers; não afetaram os testes.

## Limitações confirmadas

- Não há evidência de uma fonte real de archive da Evolution. `ChatUpdate` exige que o chamador declare archive explícito e origem verificada, mas nenhum mapper/controller foi ligado a uma fonte Evolution de archive; não se deve inferir archive de `messages.upsert`.
- O CodeGraph inicialmente encontrou os símbolos TypeScript do checkout legado e não resolveu os símbolos Java da worktree mesmo após indexação parcial. A análise foi completada com buscas e leitura direta dos arquivos locais relevantes.
- A suíte completa não foi executada; a verificação ficou no comando focado requerido pela tarefa. Nenhuma operação em VPS ou produção foi feita.

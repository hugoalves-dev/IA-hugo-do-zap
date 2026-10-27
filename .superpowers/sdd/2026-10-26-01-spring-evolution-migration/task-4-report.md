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

## Correções da revisão — 2026-10-27

- **Bootstrap concorrente:** estado recebe lock consultivo por identidade canônica antes da leitura; a canonicalização é revalidada sob lock. O `ON CONFLICT` também exige `snapshot_at` estritamente mais novo, para que uma corrida com escritores externos não reverta o estado.
- **Merge de aliases:** união explicitamente comprovada migra snapshots para a identidade canônica, seleciona o snapshot verificável mais recente, preserva o maior watermark de leitura e combina pendências (contagem, primeira/última mensagem, preview mais recente e expiração). Se o snapshot mais recente está desarquivado, as pendências são descartadas como no legado. Nomes/aliases também migram.
- **Contenção de lock:** observação individual de identidade usa `INSERT ... ON CONFLICT` sem lock de tabela. Um par de alias já unido retorna pela leitura protegida e não repete o merge. `SHARE ROW EXCLUSIVE` é usado somente para unir conjuntos de aliases distintos, operação rara que precisa reescrever o conjunto atomicamente; permite leitores, mas serializa escritores durante o merge. As transações de mensagem sem novo merge não ficam sob esse lock global.
- **Aliases distintos:** `IncomingMessage` separa `alternateChatJid` de `alternateParticipantJid`; mensagens de grupo só usam o alternativo do participante para identidade/deduplicação.
- **Eventos de leitura:** limpeza exige timestamp, snapshot conhecido e sincronizado. O watermark `last_read_at` avança monotonicamente; a pendência só é apagada quando sua última mensagem não é posterior ao READ/unread-zero recebido.
- **Nomes:** migração V5 e `IdentityService.observeContactName` preservam a precedência `CONTACT_NAME > NOTIFY > VERIFIED > CHAT/LEGACY > USERNAME > PHONE`, rejeitam nomes técnicos e fazem `ChatStateService.apply` observar nomes de chat sem rebaixar uma origem melhor.

### TDD/verificação da revisão

- **RED comportamental:** o teste concorrente com atraso real no PostgreSQL falhou antes da proteção: esperava `archived=true` após o update antigo terminar por último e observou `false`.
- Os testes de merge, separação de aliases, READ ordenado e nomes foram escritos antes das mudanças correspondentes. A primeira execução conjunta ficou vermelha em `testCompile` porque a nova assinatura de `clearRead`, os campos separados de `IncomingMessage` e a API de identidade/nome ainda não existiam. Uma execução subsequente identificou somente vazamento de `contact_identity` entre fixtures; o reset foi corrigido.
- **GREEN final:** `mvn -Dtest=ChatStateServiceTest,MessageServiceTest,IdentityServiceTest,NotificationPolicyTest,EvolutionEventMapperTest,WatcherPropertiesTest,DatabaseMigrationTest test` — **34 testes, 0 falhas, 0 erros**, com PostgreSQL 17 Testcontainers. `api.version=1.44`, Docker Desktop via named pipe e socket override foram aplicados; sem `mvn clean`.
- Persistem apenas avisos externos de depreciação `sun.misc.Unsafe` (Guice/Maven) e do lifecycle interno do Testcontainers. Nenhum teste ou migração falhou.
- A suíte completa não foi executada. O arquivo `NotificationPolicy.java` e seu teste, planos untracked e `target/` preexistentes continuam fora do stage/commit.

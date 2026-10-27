# Plano 1 — Migração do watcher para Spring Boot e Evolution API

> **Para execução:** use `superpowers:executing-plans` ou `superpowers:subagent-driven-development`; execute cada tarefa com teste vermelho, implementação e teste verde. Este plano substitui `2026-10-20-evolution-api-isolated-poc.md`: o usuário recusou a POC como etapa prévia. A validação da integração continua necessária antes do cutover.

**Objetivo:** substituir integralmente o runtime TypeScript por Java 25/Spring Boot 4.x e Evolution API, preservando o comportamento legado durante coexistência e rollback. A integração nova usa Telegram Bot API diretamente via wrapper open-source `pengrad/java-telegram-bot-api`; n8n não integra o transporte novo.

**Arquitetura:** Evolution recebe eventos do WhatsApp; webhook privado do Spring valida e persiste inbox PostgreSQL antes do ACK. Workers com leases processam eventos idempotentemente, atualizam snapshots e entregam notificações pela outbox à Telegram Bot API via wrapper pengrad. Entrega externa é at-least-once: retries após resultado incerto podem duplicar; não declarar exactly-once. O legado só é desligado no cutover autorizado.

**Stack:** Maven, Java 25 JVM, Spring Boot 4.x, PostgreSQL/Flyway, JDBC, Testcontainers, Docker Compose, Evolution API oficial fixada e Redis isolado.

**Spec:** `docs/superpower/spec/2026-10-16-archived-watcher-automation-design.md`, com a alteração explícita do usuário: sem POC separada ou gate de POC antes de implementar. Não interpretar isso como prova de compatibilidade ou autorização de deploy.

## Restrições globais

- Ao concluir e verificar cada tarefa, fazer push dos commits da tarefa para a branch de trabalho no origin.
- Ao terminar o plano 1, aguardar aprovação explícita do usuário antes de iniciar os planos 2 e 3.

- Preservar `watcher.db`, sessão, dados e produção; não executar operações de VPS, pareamento, cutover ou envio real sem autorização operacional explícita.
- Não chamar `archiveChat` ou `chatModify`. O modo de escopo tem configuração runtime `ALL` (padrão), `ARCHIVED_ONLY` ou `UNARCHIVED_ONLY`. `UNKNOWN` é considerado apenas em `ALL`; nos modos restritos, estado ausente, atrasado ou desconhecido exclui o evento com motivo auditável.
- Não afirmar completude/paridade da Evolution em `ARCHIVED_ONLY` ou `UNARCHIVED_ONLY` sem evidência específica do estado necessário. O plano 1 implementa os modos e informa precisamente qual deles teve a cobertura verificada.
- Evolution oficial primeiro; usar fork apenas depois de falha demonstrada e revisão da origem/licença. Fixar commit e digest; sem `latest`.
- Spring e Evolution usam bancos e usuários distintos na instância PostgreSQL existente; sem SQLite novo, broker, GraalVM native ou AOT.
- Sem dependência direta de Baileys no novo serviço. Contratos `archived_messages` e `whatsapp_connection_lost` pertencem ao transporte n8n histórico e só são mantidos durante coexistência/rollback; o serviço Java novo notifica Telegram diretamente via wrapper pengrad.
- Não registrar segredos, conteúdo bruto ou dados pessoais em logs; retenção de snippets no máximo 7 dias.
- Executar testes direcionados por tarefa. Suíte completa somente mediante pedido explícito do usuário; relatar a ausência de verificação ampla.
- Trabalho parcial não rastreado já existe em `pom.xml`, `src/main/java/dev/hugo/watcher/NotificationPolicy.java` e respectivo teste. Conferir e adaptar antes de reutilizar; não presumir que represente a migração completa.

## Foco da revisão

1. Evento sem `archived` ou sem sincronização inicial nunca autoriza processamento (tarefas 2 e 4).
2. Webhooks duplicados ou fora de ordem não duplicam mensagens nem revertem estado mais recente (tarefas 2–4).
3. ACK antes de commit e falhas de disco não podem perder eventos silenciosamente (tarefa 2).
4. Leitura/desarquivamento ou nova mensagem durante envio não eliminam pendências indevidamente (tarefas 4–5).
5. Reentrega externa pode ocorrer; documentar limites de idempotência e impedir duplicação entre legado e Java no corte (tarefas 5 e 7).

## Arquivos e contratos

- `pom.xml`, `src/main/java/dev/hugo/watcher/WatcherApplication.java`, `src/main/resources/application.yml`: bootstrap/configuração validada.
- `src/main/resources/db/migration/V1__watcher.sql`: tabelas `inbox_events`, `processing_jobs`, `outbox_actions`, `chat_snapshot`, `identity_aliases`, `pending_notification`, `processed_message`.
- `.../evolution/EvolutionWebhookController.java`, `EvolutionEvent.java`, `EvolutionEventMapper.java`: ingresso e mapeamento versionado.
- `.../persistence/InboxRepository.java`, `JobRepository.java`, `OutboxRepository.java`: transações, leases e reentregas.
- `.../identity/IdentityService.java`, `.../watcher/ChatStateService.java`, `MessageService.java`, `NotificationPolicy.java`: identidade, snapshots e política.
- `.../notification/NotificationService.java`, `TelegramBotClient.java` baseado em `pengrad/java-telegram-bot-api`, `ConnectionService.java`: agendamento direto, entrega at-least-once e recuperação.
- `src/test/java/dev/hugo/watcher/**`: testes por componente; `src/test/resources/fixtures/evolution/`: fixtures sintéticas explicitamente rotuladas e, quando disponíveis, schemas reais sanitizados.
- `Dockerfile`, `compose.yaml`, `.env.example`, `.gitignore`, `.dockerignore`, `README.md`: JVM e operação; manter os arquivos de legado versionados até a etapa final de remoção.

### Tarefa 1: Bootstrap, configuração e banco

**Interfaces:** `WatcherApplication`; `WatcherProperties` valida configuração da Evolution, Telegram Bot API/wrapper pengrad, timezone, dias, janelas, regex, grupos e limites; Flyway cria as tabelas acima com chaves únicas, índices, coluna `archived` tri-state e modo runtime `ALL` (padrão), `ARCHIVED_ONLY` ou `UNARCHIVED_ONLY`.

- [ ] Escrever `WatcherPropertiesTest` para credenciais/URL da Evolution e token Telegram ausentes, URL insegura, dias 1–7 e intervalo inválido; `DatabaseMigrationTest` inicia PostgreSQL real e executa Flyway duas vezes sem perda.
- [ ] Executar `mvn -Dtest=WatcherPropertiesTest,DatabaseMigrationTest test`; confirmar falha pela ausência de implementação.
- [ ] Implementar bootstrap, dependências de web/JDBC/Flyway/PostgreSQL/Testcontainers, propriedades e migração; sem alterar dependências fora do necessário à migração.
- [ ] Reexecutar os testes direcionados; esperar sucesso e registrar resultado. Commit apenas arquivos da tarefa.

### Tarefa 2: Webhook durável e jobs

**Interfaces:** `POST /internal/evolution/webhook`; `InboxRepository.accept(EvolutionEvent): Acceptance` retorna `NEW` ou `DUPLICATE` após commit; `JobRepository.claim(int, Instant, Duration): List<Job>` e `complete(UUID, UUID)` exigem lease atual.

- [ ] Criar `EvolutionWebhookControllerTest` cobrindo origem/instância não autorizada, JSON malformado, tamanho excedido, duplicata, erro de banco e sucesso; erro não cria inbox e sucesso só ocorre após commit.
- [ ] Criar `JobRepositoryTest` com PostgreSQL real: dois consumidores não reivindicam o mesmo job, lease expirado é recuperado e lease antigo não conclui job novo.
- [ ] Executar `mvn -Dtest=EvolutionWebhookControllerTest,JobRepositoryTest test` e observar falha esperada.
- [ ] Implementar limite de corpo, ingresso privado/autenticação realmente suportada ou proxy configurado, transação inbox+job e `FOR UPDATE SKIP LOCKED`; não presumir assinatura nativa nem entrega ordenada.
- [ ] Reexecutar testes; esperar sucesso. Commit dos arquivos da tarefa.

### Tarefa 3: Contrato Evolution e identidade

**Interfaces:** `EvolutionEventMapper.map(JsonNode, String distribution): List<EvolutionEvent>`; `IdentityService.observe(IdentityObservation): CanonicalIdentity`; `EvolutionEvent` inclui fonte, tipo, id verificável, timestamp, JID/aliases e `Optional<Boolean> archived`.

- [ ] Criar `EvolutionEventMapperTest` e `IdentityServiceTest`: campo ausente/nulo é UNKNOWN; true/false distintos; LID e PN só são unidos com evidência; dois remetentes de grupo distintos permanecem distintos; evento sem id estável é rejeitado ou colocado em quarentena.
- [ ] Executar `mvn -Dtest=EvolutionEventMapperTest,IdentityServiceTest test`; observar falha esperada.
- [ ] Mapear somente campos confirmados na documentação/implementação da versão fixada; snapshots não comprovados permanecem UNKNOWN. Registrar fonte do schema e fixtures como sintéticas até validação real; nunca derivar telefone de LID.
- [ ] Reexecutar testes; esperar sucesso. Commit dos arquivos da tarefa.

### Tarefa 4: Paridade de estado e seleção de mensagens

**Interfaces:** `ChatStateService.apply(ChatUpdate)` aplica eventos ordenados por versão/timestamp verificável; `MessageService.accept(IncomingMessage)` deduplica atomicamente e seleciona chats conforme modo runtime. Em `ALL` aceita `archived=true`, `false` e `UNKNOWN`; em modos restritos aceita somente estado compatível conhecido e sincronizado.

- [ ] Criar `ChatStateServiceTest` e `MessageServiceTest`: modos runtime `ALL` (padrão), `ARCHIVED_ONLY`, `UNARCHIVED_ONLY`; `UNKNOWN` só elegível em `ALL`; bootstrap desconhecido, update antigo, desarquivamento limpa pendência, eventos de leitura limpam fila, conteúdo próprio/status/protocolo é ignorado, grupos desativados são ignorados, aliases compartilham dedup, nomes bloqueados e política `WORK_ONLY` são preservados.
- [ ] Executar `mvn -Dtest=ChatStateServiceTest,MessageServiceTest,NotificationPolicyTest test`; confirmar falha onde não implementado.
- [ ] Portar comportamento de `src/watcher-state.ts`, `notify-handler.ts`, `message-filter.ts`, `contact-identity.ts`, `message-preview.ts` e `config.ts` para serviços Java transacionais. Preservar regex configurável, janela local, preview e limite de retenção; mensagens de histórico não viram mensagens novas.
- [ ] Reexecutar testes; esperar sucesso. Commit dos arquivos da tarefa.

### Tarefa 5: Outbox, Telegram direto e desconexões

**Interfaces:** `NotificationService.flush(Instant)` prepara notificações Telegram em outbox; cliente baseado em `pengrad/java-telegram-bot-api` usa timeout e política de retry; `ConnectionService.onState(ConnectionEvent)` implementa período de graça e alerta de pareamento. Transporte legado n8n fica fora da implementação nova.

- [ ] Criar `NotificationServiceTest`, teste do cliente Telegram e `ConnectionServiceTest`: destino/shape Telegram esperado, fuso/total, adiamento WORK_ONLY, exclusão condicional após ACK, nova mensagem concorrente preservada, timeout/5xx mantém outbox, retry após resultado remoto incerto admite duplicata, desconexão breve não alerta e `device_removed` alerta uma vez.
- [ ] Executar testes direcionados de notificação, cliente Telegram e conexão; confirmar falha esperada.
- [ ] Implementar agendador sem sobreposição, outbox durável e retries/backoff; documentar entrega at-least-once e não afirmar exactly-once. Preservar o legado n8n apenas para coexistência/rollback.
- [ ] Reexecutar testes; esperar sucesso. Commit dos arquivos da tarefa.

### Tarefa 6: Migração de dados, retenção e operação

**Interfaces:** `LegacyImportService.importSnapshot(Path, ImportMode): ImportReport` abre cópia SQLite somente leitura, importa identidades/pendências em transação idempotente e não copia sessão; `RetentionService.purge(Instant)` remove snippets expirados.

- [ ] Criar `LegacyImportServiceTest` com cópia SQLite sintética: duas importações não duplicam, arquivo original permanece byte a byte intacto, conflitos são relatados e não assumem archived atual; `RetentionServiceTest` verifica expiração em até 7 dias.
- [ ] Executar `mvn -Dtest=LegacyImportServiceTest,RetentionServiceTest test`; confirmar falha esperada.
- [ ] Implementar importação explicitamente acionada e purga; gerar Dockerfile JVM multi-stage, Compose com Evolution/Redis isolados e Spring usando PostgreSQL existente. Atualizar README, exemplos e healthcheck sem imprimir credenciais; não executar serviços reais.
- [ ] Reexecutar testes e `docker compose ... config --quiet` com valores sintéticos locais; registrar indisponibilidade do Docker se houver. Commit dos arquivos da tarefa.

### Tarefa 7: Integração real, corte e remoção TypeScript

- [ ] Adicionar testes de integração dirigidos para webhook → inbox → job → snapshot → outbox → fake Telegram Bot API, incluindo falha/retentativa, duplicata possível após resultado incerto e reinício. Executar e registrar resultados.
- [ ] Documentar versão/commit/digest, segurança, evidência de snapshot inicial e pós-reconexão, `archived` true/false manuais, grupos, LID/PN e perda/retry do webhook. Estes testes usam o serviço implementado; não criar harness de POC. A ausência de evidência bloqueia **ativação**, não implementação.
- [ ] Preparar checklist revisável de backup/restauração, comparação de paridade, exclusão mútua de notificações, rollback e monitoramento. Solicitar autorização separada antes de parear, alterar VPS ou fazer cutover.
- [ ] Somente depois de corte autorizado e validado, retirar `src/*.ts`, testes Node, `package*.json`, `tsconfig.json` e imagens antigas; preservar backup e rollback. Sem aprovação, entregar código Java e deixar produção intacta.

**Saída:** serviço Java testado localmente; status operacional separado dos testes. Sem evidência real, não declarar paridade Evolution completa nos modos `ARCHIVED_ONLY` ou `UNARCHIVED_ONLY`, nem cutover concluído. Ao concluir plano 1, parar e aguardar aprovação explícita do usuário antes de iniciar planos 2 ou 3.

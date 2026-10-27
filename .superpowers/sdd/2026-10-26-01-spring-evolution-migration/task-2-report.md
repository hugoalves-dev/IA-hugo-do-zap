# Relatório da tarefa 2 — webhook durável e jobs

## Resultado

Implementado `POST /internal/evolution/webhook` com autenticação Bearer em tempo constante, allowlist de uma instância configurada e limite de corpo que também cobre requisições sem `Content-Length`. Eventos autenticados são deduplicados por `(source, external_id)`; a gravação da inbox e a criação do job `READY` ocorrem na mesma transação. Respostas `202` e `200` são produzidas depois que o método transacional retorna, portanto depois do commit.

Os jobs recebem UUID público e leases com token UUID/expiração. `claim` seleciona e atualiza registros com `FOR UPDATE SKIP LOCKED`; lease expirado pode ser reivindicado outra vez. `complete` exige o UUID do job, token da lease e lease ainda vigente. A migração V2 preserva os IDs BIGINT e as linhas existentes.

## Evidência RED/GREEN

- RED: `mvn '-Dtest=EvolutionWebhookControllerTest,JobRepositoryTest' test` falhou ao compilar antes da implementação, como esperado: faltavam `JobRepository` e `JdbcJobRepository`. O RED também mostrou que `TestRestTemplate` não está no conjunto atual de dependências Boot 4; os testes passaram a usar `java.net.http`, sem adicionar dependências.
- GREEN HTTP sem banco: `mvn '-Dtest=EvolutionWebhookControllerHttpTest' test` terminou com código 0: 8 testes executados, 0 falhas/erros/pulados. Inclui origem não autenticada, instância não autorizada, JSON malformado, corpo excedido, rota não mapeada, duplicata, erro do repositório e resposta de aceite.
- Primeira execução focada: `mvn '-Dtest=EvolutionWebhookControllerTest,JobRepositoryTest,EvolutionWebhookControllerHttpTest' test` compilou tudo, mas o daemon ainda estava indisponível; 10 testes com PostgreSQL foram pulados e 8 testes HTTP passaram. Nenhum daemon foi iniciado.
- Avisos observados: depreciação preexistente de `sun.misc.Unsafe` via Guice/Maven; Mockito/ByteBuddy carregou agente dinamicamente durante o teste MockMvc. Não houve falhas nos 8 testes executados.

## Autenticação Evolution

A aplicação espera `WATCHER_EVOLUTION_WEBHOOK_TOKEN` e `WATCHER_EVOLUTION_INSTANCE`; o tamanho padrão é 1 MiB (`WATCHER_EVOLUTION_MAX_BODY_BYTES`, máximo 4 MiB). Para autenticar, configure o webhook **por instância** da Evolution API com `Authorization: Bearer <token>`. A documentação oficial descreve headers customizados e suporte a Bearer/API key/Basic em webhooks por instância: [Evolution API docs, PR #22](https://github.com/evolution-foundation/docs-evolution/pull/22). O webhook global via variáveis `WEBHOOK_GLOBAL_*` não oferece headers customizados; esse modo não atende ao Bearer desta implementação. A rede `infra_network` existente continua sem porta publicada pelo serviço watcher.

Nenhuma assinatura HMAC ou garantia de ordenação é presumida. Um evento sem `data.key.id` nem `id` superior é rejeitado com 400.

## Arquivos da tarefa

- `.superpowers/sdd/2026-10-26-01-spring-evolution-migration/task-2-report.md`
- `src/main/java/dev/hugo/watcher/Acceptance.java`
- `src/main/java/dev/hugo/watcher/EvolutionEvent.java`
- `src/main/java/dev/hugo/watcher/EvolutionWebhookController.java`
- `src/main/java/dev/hugo/watcher/EvolutionWebhookProperties.java`
- `src/main/java/dev/hugo/watcher/InboxRepository.java`
- `src/main/java/dev/hugo/watcher/Job.java`
- `src/main/java/dev/hugo/watcher/JobRepository.java`
- `src/main/java/dev/hugo/watcher/JdbcInboxRepository.java`
- `src/main/java/dev/hugo/watcher/JdbcJobRepository.java`
- `src/main/java/dev/hugo/watcher/WebhookBodyLimitFilter.java`
- `src/main/resources/db/migration/V2__durable_webhook_jobs.sql`
- `src/test/java/dev/hugo/watcher/EvolutionWebhookControllerHttpTest.java`
- `src/test/java/dev/hugo/watcher/EvolutionWebhookControllerTest.java`
- `src/test/java/dev/hugo/watcher/JobRepositoryTest.java`

## Limites e acompanhamento

- Os testes com PostgreSQL foram executados no seguimento P1 abaixo usando Docker API 1.44 e passaram. Nenhuma VPS nem instância real da Evolution foi alterada nesta tarefa.
- O token deve ser injetado por segredo de ambiente e configurado no webhook Evolution por instância; nenhuma configuração de instância/VPS foi alterada nesta tarefa.
- Não foram alterados `NotificationPolicy`, os planos não rastreados preexistentes ou `target/`.

## Seguimento P1 — identidade da entrega e auto-migração Spring

### Achado 1: deduplicação entre tipos de evento

- Causa: a constraint da V1 e o `ON CONFLICT` da inbox usavam `(source, external_id)`, embora a Evolution emita eventos distintos como `messages.upsert` e `messages.update` para o mesmo ID WhatsApp.
- RED: após preparar PostgreSQL real e o contexto de teste, `EvolutionWebhookControllerTest.deduplicatesByEventTypeSoDistinctEventsForOneMessageBothCreateJobs` falhou porque a segunda entrega recebeu `200` (duplicata) em vez de `202` (evento novo). Uma repetição da primeira entrega continua esperada como `200`.
- Correção: V3 remove a constraint antiga e aplica `UNIQUE(source, event_type, external_id)`; `JdbcInboxRepository.accept` usa os três campos no `ON CONFLICT`. A inbox e um job são criados para cada tipo distinto, mantendo a deduplicação de reentrega do mesmo tipo.

### Achado 2: Flyway não iniciava junto com o contexto

- Causa: o POM trazia `flyway-core` diretamente, sem o starter que fornece a integração de auto-configuração Flyway no Spring Boot 4. O `SpringBootTest` iniciava o DataSource, mas não criava `flyway_schema_history` nem as tabelas da aplicação. A chamada explícita do teste de migração V1/V2 mascarava essa lacuna.
- RED: `SpringFlywayStartupTest.appliesFlywayMigrationsAutomaticallyDuringApplicationStartup` falhou com `relation "flyway_schema_history" does not exist` após o contexto Spring iniciar.
- Correção: troquei a dependência direta `flyway-core` por `spring-boot-starter-flyway` do Boot 4 e mantive o módulo PostgreSQL. O teste agora verifica que a versão 3 está aplicada e que a inbox existe depois do startup. A documentação oficial do Boot 4 instrui usar `spring-boot-starter-flyway` para executar migrações na inicialização ([guia de inicialização de banco](https://docs.spring.io/spring-boot/4.0/how-to/data-initialization.html), [lista oficial de starters](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html)).

### Verificação GREEN do seguimento

- Comando: `mvn '-Dtest=EvolutionWebhookControllerHttpTest,EvolutionWebhookControllerTest,JobRepositoryTest,SpringFlywayStartupTest,DatabaseMigrationTest' test`, com `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` e `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Resultado: código 0, **21 testes executados, 0 falhas, 0 erros e 0 pulados**. Incluiu 8 testes HTTP sem banco; 8 testes de controller/PostgreSQL (incluindo duas categorias distintas e reentrega deduplicada); 3 testes de jobs concorrentes/lease; 1 teste de auto-migração do contexto; e 1 teste Flyway repetível.
- O log de `SpringFlywayStartupTest` confirma migrações V1, V2 e V3 aplicadas automaticamente durante a inicialização, com versão atual v3. O Testcontainers acessou PostgreSQL 17.11 via Docker Desktop usando o override temporário fornecido pelo executor.
- Uma tentativa anterior ao ajuste do fixture falhou antes de exercitar o bug porque `watcher.groupJids` não estava configurado; foi corrigida com uma propriedade de teste válida antes de capturar o RED funcional descrito acima.

### Arquivos adicionais do seguimento

- `pom.xml`
- `src/main/resources/db/migration/V3__deduplicate_inbox_by_event_type.sql`
- `src/test/java/dev/hugo/watcher/SpringFlywayStartupTest.java`

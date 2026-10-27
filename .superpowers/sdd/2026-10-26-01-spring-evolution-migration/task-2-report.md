# Relatório da tarefa 2 — webhook durável e jobs

## Resultado

Implementado `POST /internal/evolution/webhook` com autenticação Bearer em tempo constante, allowlist de uma instância configurada e limite de corpo que também cobre requisições sem `Content-Length`. Eventos autenticados são deduplicados por `(source, external_id)`; a gravação da inbox e a criação do job `READY` ocorrem na mesma transação. Respostas `202` e `200` são produzidas depois que o método transacional retorna, portanto depois do commit.

Os jobs recebem UUID público e leases com token UUID/expiração. `claim` seleciona e atualiza registros com `FOR UPDATE SKIP LOCKED`; lease expirado pode ser reivindicado outra vez. `complete` exige o UUID do job, token da lease e lease ainda vigente. A migração V2 preserva os IDs BIGINT e as linhas existentes.

## Evidência RED/GREEN

- RED: `mvn '-Dtest=EvolutionWebhookControllerTest,JobRepositoryTest' test` falhou ao compilar antes da implementação, como esperado: faltavam `JobRepository` e `JdbcJobRepository`. O RED também mostrou que `TestRestTemplate` não está no conjunto atual de dependências Boot 4; os testes passaram a usar `java.net.http`, sem adicionar dependências.
- GREEN HTTP sem banco: `mvn '-Dtest=EvolutionWebhookControllerHttpTest' test` terminou com código 0: 8 testes executados, 0 falhas/erros/pulados. Inclui origem não autenticada, instância não autorizada, JSON malformado, corpo excedido, rota não mapeada, duplicata, erro do repositório e resposta de aceite.
- Execução focada final: `mvn '-Dtest=EvolutionWebhookControllerTest,JobRepositoryTest,EvolutionWebhookControllerHttpTest' test` terminou com código 0 e compilou fontes de produção e teste. Relatório Maven: 18 testes, 0 falhas, 0 erros e **10 pulados**; somente os 8 testes HTTP sem banco executaram. Os 7 testes de controller com PostgreSQL e os 3 testes de jobs foram pulados porque Testcontainers não conseguiu iniciar PostgreSQL.
- Docker não pôde ser usado: o daemon respondeu ao socket nomeado com HTTP 400 e metadados vazios, e a tentativa de acesso ao Docker Desktop/WSL Ubuntu falha por acesso negado ao VHD (diagnóstico do executor). Nenhum daemon foi iniciado. Portanto **não estão verificados em PostgreSQL real** o commit inbox+job, a migração V2, `SKIP LOCKED`, recuperação da lease nem a rejeição da lease antiga.
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

- Os testes de banco devem ser reexecutados em runner com PostgreSQL real via Docker antes de considerar a persistência/concorrência validada.
- O token deve ser injetado por segredo de ambiente e configurado no webhook Evolution por instância; nenhuma configuração de instância/VPS foi alterada nesta tarefa.
- Não foram alterados `NotificationPolicy`, os planos não rastreados preexistentes ou `target/`.

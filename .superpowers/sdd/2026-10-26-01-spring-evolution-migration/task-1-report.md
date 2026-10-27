# Relatório da tarefa 1 — bootstrap, configuração e banco

## Resultado

Implementados o bootstrap Spring Boot 4, propriedades `watcher` com validação Jakarta, dependências de Web/JDBC/Validation, PostgreSQL e Flyway, e a migração inicial do schema. O banco cobre `inbox_events`, `processing_jobs`, `outbox_actions`, `chat_snapshot`, `identity_aliases`, `pending_notification` e `processed_message`, com restrições únicas e índices para deduplicação/consulta. `chat_snapshot.archived` é `BOOLEAN NULL`, preservando os estados true/false/unknown.

## Evidência RED/GREEN

- RED: `mvn '-Dtest=WatcherPropertiesTest,DatabaseMigrationTest' test` falhou na compilação antes da implementação. Os erros reportaram `WatcherProperties` e pacotes Flyway/Testcontainers ausentes no checkout inicial, conforme esperado.
- GREEN: `mvn -q '-Dtest=WatcherPropertiesTest,DatabaseMigrationTest' test` terminou com código 0. Relatórios Surefire: `WatcherPropertiesTest` 4 executados, 0 falhas/erros; `DatabaseMigrationTest` 1 pulado.
- Regressão focada: `mvn -q '-Dtest=NotificationPolicyTest' test` terminou com código 0: 2 executados, 0 falhas/erros.
- Docker não estava disponível. Testcontainers identificou ausência de ambiente Docker e pulou o teste de PostgreSQL por `disabledWithoutDocker=true`; portanto a execução real da migração contra PostgreSQL e a preservação de dados na segunda execução **não foram verificadas neste ambiente**. Nenhum daemon foi iniciado.
- A JVM/Maven emitiu aviso preexistente de depreciação do método `sun.misc.Unsafe` via Guice. Não afetou o código de saída.

## Arquivos da tarefa

- `pom.xml`
- `src/main/java/dev/hugo/watcher/WatcherApplication.java`
- `src/main/java/dev/hugo/watcher/WatcherProperties.java`
- `src/main/resources/db/migration/V1__create_watcher_schema.sql`
- `src/test/java/dev/hugo/watcher/WatcherPropertiesTest.java`
- `src/test/java/dev/hugo/watcher/DatabaseMigrationTest.java`
- `.superpowers/sdd/2026-10-26-01-spring-evolution-migration/task-1-report.md`

## Riscos e limites

- A validação permite HTTP somente para o host interno exato `n8n`; hosts internos com outros nomes precisam de HTTPS.
- As regras de negócio para quais JIDs/grupos são aceitos ainda são apenas limites de configuração; a autorização/seleção de grupos pertence às tarefas seguintes.
- A migração está escrita para PostgreSQL e usa JSONB. Seu SQL ainda precisa ser executado em PostgreSQL real assim que houver runner com Docker.
- `archived` null representa ausência de evidência/snapshot; o serviço deve continuar fail-closed enquanto esse valor for desconhecido.

## Preservação do checkout

`NotificationPolicy.java`, `NotificationPolicyTest.java`, os três planos preexistentes e `target/` não foram alterados nem incluídos no commit. O commit deve conter somente os sete arquivos listados acima.

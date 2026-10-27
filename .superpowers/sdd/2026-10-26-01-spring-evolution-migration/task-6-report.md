# Relatório da tarefa 6 — migração, retenção e operação

Base do worktree: `d642b5a`.

## Implementação

- `LegacyImportService.importSnapshot(Path, ImportMode)` abre uma cópia SQLite com driver read-only e `PRAGMA query_only`; exige o esquema suportado e lê apenas `contact_identity`, `pending_notification` e metadados de grupo/política. Nunca consulta nem importa `chat_state.archived`.
- A aplicação PostgreSQL é atômica. Reimportações idênticas são idempotentes; colisões de aliases, anchors únicos ou pendências retornam conflitos sanitizados e revertem a transação. O `DRY_RUN` não grava e informa candidatos previstos.
- O comando one-shot é acionado somente com `watcher.legacy-import.snapshot`. Os exemplos passam `watcher.runtime.scheduling-enabled=false`, para impedir execução de workers agendados enquanto o processo de importação inicia.
- A implementação de retenção agora está exposta como `RetentionService`; o nome anterior permanece como wrapper de compatibilidade. Snippets expirados são removidos e dados sensíveis seguem limitados a sete dias.
- Dockerfile multi-stage usa Java 25/JVM e healthcheck Actuator sem detalhes. Compose não provisiona PostgreSQL, coloca Spring/Evolution na rede externa existente, mantém Redis em rede interna isolada e não publica portas. Evolution está fixada em `v2.3.7` com digest; o manifesto de implantação oficial usa essa tag ([configuração oficial Evolution](https://github.com/evolution-foundation/evolution-api/blob/main/Docker/swarm/evolution_api_v2.yaml)). Redis também está fixado por digest.
- README e `.env.example` documentam credenciais sintéticas, bancos/usuários distintos, configuração do webhook e cópia read-only do banco legado. Os nomes dos serviços recebem somente as variáveis explícitas de que precisam.

## Verificação

- RED→GREEN: `LegacyImportServiceTest` cobre idempotência, hash byte a byte da cópia, ausência de importação do archive legado, dry-run sem escrita, conflitos de alias/anchor e rollback quando uma pendência concorrente diverge em `due_at`.
- RED→GREEN: `RetentionServiceTest` usa PostgreSQL/Testcontainers e confirma remoção expirada e limite máximo de sete dias.
- `mvn -Dtest=LegacyImportServiceTest,RetentionServiceTest test` — **6 testes, 0 falhas/erros/ignorados**.
- `mvn -Dtest=LegacyImportRunnerTest,WatcherSchedulingConfigurationTest test` — **4 testes, 0 falhas/erros/ignorados**.
- `docker compose --env-file .env.example config --quiet` — **exit 0**, sem saída.
- `git diff --check` — **exit 0**.

O Maven emitiu avisos de depreciação/agent de Mockito, acesso nativo SQLite e encerramento de extensão Testcontainers; nenhuma execução de teste falhou por esses avisos. CodeGraph informou que o worktree não estava indexado; a análise de impacto usou buscas textuais limitadas e inspeção direta dos serviços/testes relacionados.

## Limites operacionais respeitados

Não abri nem modifiquei o banco legado real; o único SQLite acessado foi fixture sintética em diretório temporário. Não rodei `docker compose up`, não iniciei Evolution/Redis/serviço Spring, não fiz VPS, pareamento ou envio real. Não rodei suíte completa.

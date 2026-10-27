# Task 5 — Outbox, n8n e desconexões

Base: `1221670a8b1bb37205dced0d9c8cfa3d01f26f96`.

## Implementação

- Estendida a tabela PostgreSQL `outbox_actions` na V7 com snapshots de pending, lease token, prazo de retenção e backoff. `FOR UPDATE SKIP LOCKED` distribui claims; ACK apaga pending somente quando contagem, horários, `updated_at` e estado arquivado ainda correspondem ao snapshot.
- `NotificationService.flush(Instant)` cria o payload legado `archived_messages`, limita o lote a 50 por padrão, aplica fuso e janela legada (08:00–12:00 e 13:30–17:30), adia `WORK_ONLY` e descarta grupos bloqueados.
- `N8nClient.send(OutboxAction)` usa POST JSON, timeout de 10 s e três tentativas com pausas de 1 s e 3 s. O segredo atual entra no corpo HTTP, mas não é persistido na outbox nem escrito em log. Falhas preservam a ação e aplicam backoff entre flushes. O ACK remove payload e snapshot; ações não confirmadas expiram em até sete dias.
- `ConnectionService.onState(ConnectionEvent)` persiste uma geração de indisponibilidade, espera 60 s para queda recuperável e enfileira `device_removed` imediatamente uma vez por indisponibilidade. `NotificationScheduler` evita sobreposição local e processa a outbox.
- A conexão usa um evento normalizado. Não foi ligada a um evento Evolution específico, pois a fonte real arquivada não foi verificada.

## RED

- O primeiro `mvn -Dtest=NotificationServiceTest,N8nClientTest,ConnectionServiceTest test` falhou no `testCompile` pelas APIs ainda ausentes (`NotificationService`, `N8nClient`, `ConnectionService`, `ConnectionEvent`, repositório e ação outbox).
- O teste de retenção falhou antes da mudança porque a ação expirada permanecia no banco (`expected 0, was 1`).
- O teste de grupo bloqueado observou um POST contendo o grupo `233/234` antes da regra de exclusão.
- O teste de segredo encontrou `fixture-secret` na coluna JSONB da outbox antes de mover a injeção do segredo para o cliente HTTP.
- O teste de ACK encontrou o corpo persistido após confirmação antes da limpeza imediata do payload.
- `SpringFlywayStartupTest` revelou seleção inválida de construtor para beans com construtores auxiliares; a injeção explícita foi adicionada e verificada.

## GREEN

- `mvn -Dtest=NotificationServiceTest,N8nClientTest,ConnectionServiceTest test` — **10 testes, 0 falhas, 0 erros, 0 skips**; migrações V1–V7 aplicadas em PostgreSQL 17 via Testcontainers. O servidor HTTP usado é loopback fake.
- `mvn -Dtest=SpringFlywayStartupTest test` — **1 teste, 0 falhas, 0 erros, 0 skips**; contexto Spring inicia e Flyway chega à V7.
- `mvn -Dtest=N8nClientTest test` — **2 testes, 0 falhas, 0 erros, 0 skips**, incluindo timeout e respostas 503. O teste de timeout usa prazo maior no cliente fake para evitar depender da latência de chegada ao servidor.

## Limites

- A entrega é at-least-once: se n8n processar uma requisição cuja resposta se perca ou expire, a outbox pode reenviar. Não há alegação de exactly-once no destino.
- Não houve acesso à VPS, chamada a n8n real, envio de mensagem real nem verificação de integração com uma versão Evolution específica.
- A suíte completa não foi executada.

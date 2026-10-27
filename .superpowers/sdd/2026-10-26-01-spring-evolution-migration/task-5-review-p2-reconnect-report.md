# Tarefa 5 — cancelamento de alertas após reconexão

## Correção

- Ao observar `CONNECTED`, `ConnectionService` cancela, na mesma transação do estado, apenas ações `whatsapp_connection_lost` em `PENDING`. O cancelamento apaga payload/snapshot sensíveis, expira o registro imediatamente e marca `CANCELLED`. Ações de outros tipos permanecem intactas.
- Ações `PROCESSING` mantêm status, token e prazo de lease durante a reconexão. `NotificationService` verifica estado e geração atual antes da entrega. Para alertas de conexão, `N8nClient` repete essa verificação antes de cada POST interno; depois de uma resposta falha, o worker cancela a ação com o lease token apenas se a reconexão tornou o alerta obsoleto.
- O evento `open` já não pode deixar um alerta antigo reaparecer após o backoff: uma ação vencida que voltar a ser claimada também falha na verificação de estado/geração.

## RED

```powershell
mvn '-Dtest=NotificationServiceTest,ConnectionServiceTest' test
```

Resultado: **17 testes, 2 falhas**. A reconexão deixou uma ação de conexão `PENDING`, e um 503 no fake HTTP ainda fez três POSTs apesar de o hook reconectar no primeiro request.

## GREEN

```powershell
mvn '-Dtest=NotificationServiceTest,N8nClientTest,ConnectionServiceTest,EvolutionWebhookControllerHttpTest,SpringFlywayStartupTest,DatabaseMigrationTest' test
```

Resultado: **32 testes, 0 falhas, 0 erros, 0 ignorados**; PostgreSQL 17/Testcontainers e HTTP fake em loopback. As regressões verificam que reconnect cancela apenas alertas de conexão pendentes, mantém um lease `PROCESSING` sem alteração, interrompe retry HTTP após o primeiro 503, apaga o payload cancelado e não faz POST numa execução posterior.

## Limite durante HTTP em andamento

Nenhum worker consegue cancelar ou recolher um request já iniciado em n8n. Se o request em voo responder 2xx depois do `open`, o destino ainda pode ter processado o alerta. Se falhar, a implementação interrompe as tentativas seguintes, cancela a ação claimada com o token do lease e não agenda retry no outbox. O cancelamento em reconnect não modifica leases ativos nem outras ações. A persistência continua at-least-once quando o destino processa mas a resposta não é observada.

Somente os testes focados listados foram executados; a suíte completa não foi executada.

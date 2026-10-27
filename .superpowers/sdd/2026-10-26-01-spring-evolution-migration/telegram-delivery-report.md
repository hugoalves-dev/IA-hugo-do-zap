# Entrega direta de notificações pelo Telegram

## Escopo

O Spring Boot envia notificações do outbox diretamente à Telegram Bot API. `N8nClient` e suas propriedades, variáveis Compose e teste de webhook foram removidos do caminho Java. O cliente adotado é `com.github.pengrad:java-telegram-bot-api:10.3.0`; o [repositório do projeto](https://github.com/pengrad/java-telegram-bot-api) declara licença Apache-2.0 e a [documentação do Telegram](https://core.telegram.org/bots/samples) o relaciona entre bibliotecas comunitárias.

O `NotificationService` mantém o outbox Postgres, as verificações atuais de archive mode, modo `ALL`, sincronização, grupos, regex, janelas locais e período de graça de conexão. Cada lease realiza uma tentativa ao transporte. Falhas retornam ao outbox com backoff persistente; uma resposta ambígua ainda pode causar reentrega, portanto não há garantia exactly-once.

`WATCHER_TELEGRAM_BOT_TOKEN` e o ID privado `WATCHER_TELEGRAM_CHAT_ID` são obrigatórios. O token permanece em memória no adapter, não é inserido no payload persistido e os logs registram somente código/tipo de falha. As mensagens incluem resumos de conversas arquivadas e instruções para perda de conexão ou pareamento manual.

Os testes injetam `TelegramTransport` falso e não chamam a rede Telegram. Nenhuma VPS, conta Telegram ou Evolution real foi acessada. O runtime TypeScript legado mantém seu caminho n8n até a tarefa 7/cutover autorizado do plano 1.

## Arquivos

- `pom.xml`: dependência Apache-2.0 `java-telegram-bot-api` 10.3.0.
- `src/main/java/dev/hugo/watcher/TelegramBotApiTransport.java`, `TelegramTransport.java`, `TelegramMessageFormatter.java`: adapter, seam de teste e mensagens limitadas a 3.500 caracteres.
- `NotificationService.java`, `WatcherProperties.java`: entrega direta, retry do outbox e configuração Telegram; removida configuração n8n.
- `compose.yaml`, `.env.example`, `README.md`: variáveis de token/chat privado, instruções e semântica de reentrega.
- Testes focados: transporte falso em integração/PostgreSQL, resumos e alertas, propriedades; teste antigo de webhook removido. A migração v12 adiciona o estado compartilhado do rate limit Telegram.

## Verificação

- `mvn "-Dtest=TelegramMessageFormatterTest,WatcherPropertiesTest,NotificationServiceTest,EvolutionPipelineIntegrationTest,ConnectionServiceTest,EvolutionWebhookControllerTest,SpringFlywayStartupTest" test` — 57 testes, 0 falhas, 0 erros, 0 ignorados. Inclui PostgreSQL 17/Testcontainers e fake Telegram.
- `docker compose --env-file .env.example config --quiet` — sucesso; nenhum container iniciado.
- `git diff --check` — sem erros de whitespace.

## Revisão P2: limite e retry_after

- A API wrapper expõe `BaseResponse.parameters().retryAfter()` para respostas Telegram 429. O adapter agora traduz esse valor em duração e o outbox agenda `available_at` para `retry_after + 250 ms`, persistindo ao mesmo tempo um cooldown global no PostgreSQL. Esse cooldown impede que qualquer outra linha da fila seja enviada antes da janela do Telegram.
- O limitador PostgreSQL serializa claims com uma linha singleton compartilhada por todos os workers e processos; o intervalo padrão é 1.100 ms, e o construtor de teste permite controlar o intervalo. Assim, um flush com muitas ações não cria burst de chamadas à Telegram.
- Testes falsos cobrem a extração de `retry_after`, atraso persistido, ausência de envio antes do deadline e coordenação do intervalo entre instâncias do repositório. Os testes não chamam a API Telegram.
- A alegação de quebra de surrogate no limite baixo não se confirma: `substring(0, end)` exclui o índice `end`, mas inclui o low surrogate em `end - 1`, junto com o high em `end - 2`. Quando `end - 1` é high, o código reduz `end` e evita incluir um par incompleto. Dois testes Unicode posicionam um emoji exatamente nas duas fronteiras e verificam UTF-16 bem formado; passaram sem alteração no truncamento de produção.
- Verificação focada desta revisão: `TelegramBotApiTransportTest`, `TelegramMessageFormatterTest`, `NotificationServiceTest` e `SpringFlywayStartupTest` — 36 testes; `EvolutionPipelineIntegrationTest` e `DatabaseMigrationTest` — 10 testes. Ambos os comandos passaram.

### Ajustes adicionais após revisão

- Respostas 429 agora mantêm o marcador `rateLimited` mesmo sem `retry_after`; nesse caso, ação e cooldown global usam 60 s mais margem de 250 ms. Falha sem status 429 continua usando apenas o backoff exponencial normal.
- Claims comparam o cooldown com `clock_timestamp()` do PostgreSQL e reservam o próximo envio a partir do mesmo relógio. Deadlines de 429 com e sem `retry_after` também são calculados pelo relógio do banco dentro da transação; clocks adiantados de workers não liberam envios antes do deadline.
- Testes PostgreSQL cobrem worker adiantado, deadline baseado em relógio do banco, 429 sem parâmetro, bloqueio de outras linhas da outbox e 429 com `retry_after`. A validação focada final (`TelegramBotApiTransportTest`, `NotificationServiceTest`, `EvolutionPipelineIntegrationTest`, `SpringFlywayStartupTest`) passou: 40 testes, 0 falhas, 0 erros.

### Elegibilidade e leases da outbox

- `claimNext` agora captura `clock_timestamp()` em sua transação PostgreSQL e usa esse instante para verificar `available_at`, `sensitive_expires_at` e lease expirado. Também calcula `lease_expires_at` e `updated_at` a partir do banco.
- Retry genérico calcula `available_at` e `updated_at` a partir do relógio PostgreSQL. Clocks adiantados não antecipam retries nem recuperam leases ativos; clocks atrasados não prolongam elegibilidade de payloads já vencidos.
- Testes PostgreSQL reproduzem retry com clock adiantado, lease ativo/expirado e expiração sensível com clock atrasado. As fixtures simulam passagem do deadline atualizando a linha do banco.
- Verificação focada: `NotificationServiceTest`, `EvolutionPipelineIntegrationTest`, `SpringFlywayStartupTest`, `TelegramBotApiTransportTest` — 43 testes, 0 falhas, 0 erros.

O branch não foi enviado nem publicado. A revisão e o push ficam para o responsável pelo plano.

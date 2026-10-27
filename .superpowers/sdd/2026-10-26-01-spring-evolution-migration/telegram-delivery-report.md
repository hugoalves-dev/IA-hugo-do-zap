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
- Testes focados: transporte falso em integração/PostgreSQL, resumos e alertas, propriedades; teste antigo de webhook removido. A expectativa do teste Flyway foi corrigida de v10 para v11, que é a última migração já presente.

## Verificação

- `mvn "-Dtest=TelegramMessageFormatterTest,WatcherPropertiesTest,NotificationServiceTest,EvolutionPipelineIntegrationTest,ConnectionServiceTest,EvolutionWebhookControllerTest,SpringFlywayStartupTest" test` — 57 testes, 0 falhas, 0 erros, 0 ignorados. Inclui PostgreSQL 17/Testcontainers e fake Telegram.
- `docker compose --env-file .env.example config --quiet` — sucesso; nenhum container iniciado.
- `git diff --check` — sem erros de whitespace.

O branch não foi enviado nem publicado. A revisão e o push ficam para o responsável pelo plano.

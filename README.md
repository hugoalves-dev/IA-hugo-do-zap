# WhatsApp archived watcher

Serviço Spring Boot 4 / Java 25 que recebe eventos da Evolution API, grava-os em PostgreSQL e envia notificações diretamente pela Telegram Bot API. A Evolution API usa outro banco e uma chave própria. Redis é uma dependência privada da Evolution e não tem porta publicada.

O Compose não provisiona PostgreSQL: ele usa o serviço existente pela `infra_network`. Crie bancos e usuários distintos para Spring e Evolution antes de uma implantação autorizada. A URL PostgreSQL da Evolution deve usar percent-encoding em senhas com caracteres reservados.

## Preparação operacional

1. Copie `.env.example` para `.env`, substitua cada valor `replace-*` e mantenha `.env` fora do Git. Não reutilize usuário, banco ou senha entre Spring e Evolution.
2. Confirme que a rede Docker externa `infra_network` e os dois bancos PostgreSQL já existem.
3. A imagem Evolution está fixada em `v2.3.7` e digest. Atualizações devem validar eventos e compatibilidade antes de trocar ambos. O Redis também está fixado por digest.
4. Configure o webhook por instância na Evolution para `http://watcher:8080/internal/evolution/webhook`, com o cabeçalho `Authorization: Bearer <WATCHER_EVOLUTION_WEBHOOK_TOKEN>` e o mesmo identificador de instância de `WATCHER_EVOLUTION_INSTANCE`. Não habilite webhook global. A chave de API `EVOLUTION_API_KEY` é distinta do token do webhook.
5. O serviço não cria sessão nem pareia automaticamente. Pareamento, validação do payload real, cutover e envio de notificações são passos operacionais separados e exigem autorização explícita.

Configure `WATCHER_TELEGRAM_BOT_TOKEN` com o token fornecido pelo BotFather e `WATCHER_TELEGRAM_CHAT_ID` com o ID da conversa privada autorizada. Mantenha o token somente no ambiente de execução; ele não entra no PostgreSQL nem nos logs. O bot envia resumos legíveis de mensagens arquivadas e alertas de perda de conexão.

## Modos de simulação e ações reais

Por padrão, `WATCHER_SIMULATION_ENABLED=true` permite o modo shadow. Para pausá-lo globalmente, defina `WATCHER_SIMULATION_ENABLED=false`; isso impede novos lotes e o agendador. O administrador também pode pausar simulação por `telegram_admin_settings.simulation_enabled`. A variável de ambiente não força esse ajuste do banco para `true`: ambos precisam permitir processamento.

Envio real exige opt-in explícito nas três variáveis abaixo. Todas começam em `false`, e as credenciais Evolution só são validadas quando os três opt-ins estão ligados:

```dotenv
WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_BASE_URL=https://evolution.example.invalid
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_INSTANCE=watcher-evolution
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_API_KEY=replace-with-evolution-api-key
```

Para ativar, ajuste explicitamente os três flags para `true`, informe URL HTTPS e credenciais válidas, e reinicie o serviço. Isso não ignora decisão elegível, consentimento/autorização explícita da conversa, template aprovado, política corrente nem o kill switch persistido `automation_settings.kill_switch_enabled`. Shadow e envio real são independentes: desligar shadow não ativa envio real, e ligar shadow não ativa envio real.

Uma resposta HTTP 2xx da Evolution significa apenas que a API aceitou a requisição. Sem recibo autenticado de entrega, o resultado permanece `UNKNOWN`, não há reenvio automático, e o estado não deve ser tratado como confirmação de entrega.

O cliente usa [`java-telegram-bot-api` 10.3.0](https://github.com/pengrad/java-telegram-bot-api), projeto comunitário listado na [documentação do Telegram](https://core.telegram.org/bots/samples), sob licença Apache-2.0.

O container Spring não publica porta no host. Spring e Evolution compartilham a rede externa `infra_network`; Redis fica somente na rede interna `evolution_backend`, sem acesso a partir do watcher ou do host. O healthcheck consulta `/actuator/health`; os detalhes são ocultados. Mensagens e histórico não são persistidos pela configuração da Evolution (`DATABASE_SAVE_DATA_NEW_MESSAGE=false`, `DATABASE_SAVE_MESSAGE_UPDATE=false` e `DATABASE_SAVE_DATA_HISTORIC=false`).

Depois da preparação e autorização operacional, o Compose pode ser validado sem iniciar containers:

```sh
docker compose --env-file .env config --quiet
```

Uma implantação autorizada usaria `docker compose --env-file .env up -d --build`; essa tarefa não executa esse comando.

## Importação explícita de snapshot legado

Nunca passe o caminho do banco legado ativo. Crie uma cópia SQLite consistente para importação em local protegido sem gravar no original. Por exemplo, com o banco legado parado de forma autorizada:

```sh
sqlite3 -readonly /caminho/legado/watcher.db '.backup /caminho/protegido/migration-copy/watcher.db'
```

Monte somente a cópia em modo somente leitura e execute primeiro `DRY_RUN`:

```sh
docker compose --env-file .env run --rm --no-deps \
  -v "$(pwd)/migration-copy:/migration:ro" watcher \
  --watcher.legacy-import.snapshot=/migration/watcher.db \
  --watcher.legacy-import.mode=DRY_RUN
```

Após revisar contagens e resolver qualquer conflito, use `APPLY` com o mesmo snapshot. Conflito em alias, anchor de identidade ou pendência aborta toda a transação; o relatório contém somente um fingerprint curto, tipo e motivo, sem JID ou conteúdo. A migração importa identidades e metadados de pendências, nunca sessão, conteúdo de chat ou o campo legado `archived`. A pendência só pode ser notificada depois de um snapshot Evolution atual, sincronizado e arquivado.

```sh
docker compose --env-file .env run --rm --no-deps \
  -v "$(pwd)/migration-copy:/migration:ro" watcher \
  --watcher.legacy-import.snapshot=/migration/watcher.db \
  --watcher.legacy-import.mode=APPLY
```

O importador abre a cópia em modo SQLite read-only e `query_only`; repetições idênticas não duplicam linhas. Snippets vencidos são descartados, e a retenção máxima continua limitada a sete dias. Os arquivos legados originais permanecem versionados e não são alterados.

## Testes focados

```sh
mvn -Dtest=TelegramMessageFormatterTest,WatcherPropertiesTest,NotificationServiceTest,EvolutionPipelineIntegrationTest test
```

Os testes de persistência usam PostgreSQL real via Testcontainers e um transporte Telegram falso injetado. Eles não iniciam Evolution API, não chamam a Telegram Bot API e não executam pareamento ou envio real.

O outbox do Telegram pode repetir após falha ambígua e entregar a mesma mensagem novamente; a API não oferece garantia de exactly-once. A ação WhatsApp mantém resultado ambíguo como `UNKNOWN` e não tenta reenviar automaticamente.

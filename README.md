# Hugo do ZAP 🤖

**[☕ Buy me a coffee](https://buymeacoffee.com/vitorhugo1207)** · [Repositório](https://github.com/hugoalves-dev/IA-hugo-do-zap)

Assistente pessoal para **conversas arquivadas do WhatsApp**: monitora mensagens novas elegíveis, encaminha notificações ao Telegram, respeita disponibilidade e horário de trabalho, e permite avaliar solicitações com IA em **Shadow Mode**, antes de qualquer ação real.

**Stack do backend:** Spring Boot 4 / Java 25. O watcher antigo em TypeScript/Node.js + Baileys foi removido; a integração atual usa Evolution API e Spring.

**Fluxo:** WhatsApp → Evolution API → webhook autenticado Spring → RabbitMQ → PostgreSQL + processamento Spring → Telegram Bot API. O OpenRouter é o classificador principal; Gemini é fallback técnico opcional. O antigo watcher Node/Baileys foi aposentado, e o n8n não faz parte do runtime de entrega atual.

> [!WARNING]
> A Evolution API usa uma integração **não oficial** do WhatsApp, que pode desconectar, mudar de comportamento ou levar a restrições da conta. **Pareamento não habilita envio automático.** Por padrão, IA e ações reais exigem ativação e autorização explícitas.

## Sumário

- [Como usar e configurar o básico](#como-usar-e-configurar-o-básico)
- [Como desenvolver](#como-desenvolver)
- [Comandos do Telegram](#comandos-do-telegram)
- [Variáveis de ambiente](#variáveis-de-ambiente)
- [Tecnologias e decisões](#tecnologias-e-decisões)
- [Limitações de RabbitMQ e Evolution API](#limitações-de-rabbitmq-e-evolution-api)
- [Licenças](#licenças)

## Como usar e configurar o básico

**Pré-requisitos:** Docker Engine + Docker Compose; uma rede externa `infra_network`; PostgreSQL acessível, com **bancos e roles separados** para watcher e Evolution; token de bot do [BotFather](https://t.me/BotFather). O perfil `prod` usa **PostgreSQL já existente**, não cria bancos nem usuários.

1. Clone e prepare a configuração privada:

   ~~~bash
   git clone https://github.com/hugoalves-dev/IA-hugo-do-zap.git
   cd IA-hugo-do-zap
   cp .env.example .env
   ~~~

2. No `.env`, substitua **todos** os `replace-with-*` e os endereços fictícios. Ajuste `SPRING_DATASOURCE_*`, `EVOLUTION_DATABASE_URL`, `EVOLUTION_API_KEY`, `WATCHER_EVOLUTION_WEBHOOK_TOKEN`, `RABBITMQ_PASSWORD`, `WATCHER_TELEGRAM_BOT_TOKEN` e `WATCHER_TELEGRAM_CHAT_ID`. Senhas, chaves e tokens devem ser distintos. Nunca coloque o `.env` no Git.

3. Verifique os bancos e a rede externa; crie a rede **somente em host novo**, não recrie uma rede compartilhada já existente:

   ~~~bash
   docker network inspect infra_network >/dev/null 2>&1 || docker network create infra_network
   docker compose --env-file .env config --quiet
   docker compose --env-file .env up -d
   docker compose --env-file .env ps
   ~~~

4. Conecte o WhatsApp pelo **Evolution Manager**: a porta do Compose fica ligada a `127.0.0.1:8080`. Na VPS, use `ssh -N -L 8080:127.0.0.1:8080 usuario@servidor` e abra `http://localhost:8080/manager`. Crie a instância indicada por `WATCHER_EVOLUTION_INSTANCE`, use o QR Code do Manager, confirme estado `open` e configure **webhook da instância** apontando para `http://watcher:8080/internal/evolution/webhook` com `Authorization: Bearer <WATCHER_EVOLUTION_WEBHOOK_TOKEN>`. Não use webhook global, não exponha Manager/QR publicamente e não copie sessões.

   Para um novo pareamento, a API Evolution oferece `POST /instance/create` (criação da instância) e `GET /instance/connect/watcher-evolution` (solicitação do QR Code para a instância padrão). Use a instância e a chave configuradas e não recrie uma instância que já esteja conectada. No celular, acesse **WhatsApp → Aparelhos conectados** para ler o QR Code. **Nunca publique, envie em chat ou registre captura do QR Code**: ele concede acesso à sessão e não deve aparecer nos logs ou no Git.\n\n5. Para comandos administrativos, configure `WATCHER_TELEGRAM_ADMIN_USER_ID` e `WATCHER_TELEGRAM_CHAT_ID` de uma conversa privada. Ative **um único** método: `WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED=true` **ou** `WATCHER_TELEGRAM_ADMIN_WEBHOOK_ENABLED=true`. No primeiro, nenhum outro consumidor `getUpdates` pode ficar ativo; no segundo, siga o [runbook do webhook Telegram](docs/telegram-webhook-migration.md). Recrie apenas o watcher ao mudar `.env`:

   ~~~bash
   docker compose --env-file .env up -d --no-deps --force-recreate watcher
   docker compose --env-file .env logs --tail=100 watcher
   ~~~

6. Envie `/status` e `/menu` no Telegram privado autorizado. O envio legado independe da IA. Para habilitar **somente classificação Shadow**, com controles de privacidade e custos, use o [checklist de ativação da IA](docs/local-ai-activation-checklist.md). Shadow não envia mensagens automáticas no WhatsApp.

Para detalhes de segurança, migração, observabilidade, backups, incidentes e operação real, consulte a [referência operacional avançada](docs/operations/technical-reference.md).

## Como desenvolver

**Requisitos:** Java **25**, Maven, Docker/Compose e Docker disponível para os testes de persistência com **Testcontainers**. O `mvn verify` não precisa conectar WhatsApp, Evolution ou Telegram reais.

~~~bash
git clone https://github.com/hugoalves-dev/IA-hugo-do-zap.git
cd IA-hugo-do-zap
mvn verify
~~~

Para subir o stack **somente em ambiente local**, copie `.env.example` para um `.env` de desenvolvimento, defina credenciais distintas, troque as URLs para `postgres-local-watcher:5432` e `postgres-local-evolution:5432` dentro da rede Docker e habilite `local-db` junto do `prod`:

~~~bash
docker compose --env-file .env --profile local-db up -d
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=100 watcher
~~~

O perfil `local-db` **não publica portas PostgreSQL no host**. Para executar `mvn spring-boot:run` fora do Docker, disponibilize um banco de desenvolvimento acessível pelo host e configure corretamente a conexão.

- **Aplicação:** `src/main/java/dev/hugo/watcher/`.
- **Testes:** `src/test/java/`, com PostgreSQL real via Testcontainers para os casos de integração.
- **Migrações:** `src/main/resources/db/migration/` com Flyway.
- **Configuração:** `src/main/resources/application.properties`, `compose.yaml` e `.env.example`.
- **CI/CD:** [workflow](.github/workflows/backend.yml) executa `mvn verify` em PRs. **Push em `main` dispara o deploy de produção somente do watcher**; crie branches para desenvolver. O workflow não recria Evolution nem RabbitMQ.
- **Dados:** nunca commite `.env`, QR Code, sessões, dumps, JIDs, textos de mensagens ou logs com segredos.

## Comandos do Telegram

Apenas o administrador autorizado em **chat privado** pode gerenciar o bot. Alterações sensíveis exigem botão de confirmação ou `/confirm <token>` em até cinco minutos. `/menu` mostra os caminhos disponíveis.

| Comando | O que faz |
|---|---|
| `/start`, `/menu`, `/settings` | Menus e navegação. |
| `/status` | Estado efetivo, emergência e modos. |
| `/settings_ai`, `/settings_notifications`, `/settings_prompt` | Configurações de IA, notificações e prompts. |
| `/regras_notificacao` | Consultar regras por marcador ou contato. |
| `/regra_notificacao marcador JC WORK_ONLY NORMAL` | Exemplo: contato com marcador JC só no expediente. |
| `/regra_notificacao marcador CH HERDAR PRIORITY` | Exemplo: prioridade alta com política de horário herdada. |
| `/regra_notificacao contato <jid> HERDAR PRIORITY` | Regra para JID específico. |
| `/regra_notificacao remover marcador JC` | Excluir regra por marcador. |
| `/ocupado 2h`, `/disponivel 1h`, `/ferias` | Disponibilidade temporária. |
| `/simulacao on`, `/simulacao off` | Pausar/reativar Shadow, sem alterar o teto do ambiente. |
| `/relatorio` | Resumo agregado atualizado. |
| `/ultimas 5`, `/decisao <id>` | Consultar decisões retidas, sem chamar o modelo novamente. |
| `/triagem` | Configurar triagem das notificações legadas. |
| `/perfis`, `/perfil`, `/contato` | Consultar e ajustar perfis e conversas. |
| `/templates`, `/template` | Gerenciar templates autorizados. |
| `/feriados`, `/feriado` | Consultar e configurar feriados. |
| `/apelido <jid> <nome>`, `/apelido <jid> remover` | Apelido local de contato. |
| `/orcamento` | Orçamento e custos de IA. |
| `/contexto`, `/contexto_default` | Ajustar/restaurar contexto. |
| `/prompt`, `/prompt_current`, `/prompt_history` | Prompt ativo e histórico. |
| `/prompt_new`, `/prompt_clear`, `/prompt_preview`, `/prompt_cancel` | Editar ou abandonar rascunho. |
| `/prompt_generate <instruções>`, `/prompt_refine <instruções>` | Gerar/refinar rascunho por IA, sem ativar ações reais. |
| `/prompt_compare <v1> <v2>`, `/prompt_test <revisão>` | Comparar e testar revisões. |
| `/prompt_publish`, `/prompt_restore <revisão>`, `/prompt_reset` | Publicar, restaurar ou resetar prompt com confirmação. |
| `/emergencia`, `/recuperar` | Bloqueio de emergência e recuperação confirmada. |
| `/confirm <token>` | Confirma a operação pendente. |

`NORMAL` e `PRIORITY` são **prioridades de fila**, não níveis semânticos de urgência classificados pela IA. `PRIORITY` não ignora permissões e `WORK_ONLY` não habilita IA por conta própria. `/prompt_refine` modifica o classificador, mas não define regras reais de envio.

## Variáveis de ambiente

A tabela documenta as **68 variáveis em [.env.example](.env.example)** e mais **duas** opções avançadas de RabbitMQ lidas pelo `application.properties`. Os valores exibidos pertencem ao **exemplo do repositório**, não à sua VPS. `configurar` indica placeholder que precisa ser substituído. Parâmetros internos definidos diretamente em `compose.yaml` (como `SERVER_PORT` e `SPRING_RABBITMQ_HOST`) não são escolhas adicionais do operador no `.env`.

| Grupo | Variável | Valor do exemplo / padrão | Descrição |
|---|---|---|---|
| Geral | `COMPOSE_PROFILES` | `prod` | Perfis Docker Compose ativos |
| Geral | `WATCHER_IMAGE` | `ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest` | Imagem GHCR do watcher |
| Geral | `TZ` | `America/Sao_Paulo` | Fuso do container |
| Geral | `WATCHER_TIMEZONE` | `America/Sao_Paulo` | Fuso das regras e horários |
| Geral | `SPRING_DATASOURCE_URL` | configurar | URL JDBC do PostgreSQL do watcher |
| Geral | `SPRING_DATASOURCE_USERNAME` | `watcher_app` | Usuário do PostgreSQL do watcher |
| Geral | `SPRING_DATASOURCE_PASSWORD` | configurar | Senha do PostgreSQL do watcher |
| Geral | `EVOLUTION_DATABASE_URL` | configurar | Conexão PostgreSQL exclusiva da Evolution |
| Telegram e webhook | `WATCHER_TELEGRAM_BOT_TOKEN` | configurar | Token do bot criado no BotFather |
| Telegram e webhook | `WATCHER_TELEGRAM_CHAT_ID` | `123456789` | ID do chat privado que recebe notificações |
| Telegram e webhook | `WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED` | `false` | Habilita getUpdates para comandos administrativos |
| Telegram e webhook | `WATCHER_TELEGRAM_ADMIN_WEBHOOK_ENABLED` | `false` | Habilita recebimento de comandos por webhook HTTPS |
| Telegram e webhook | `WATCHER_TELEGRAM_ADMIN_WEBHOOK_SECRET` | vazio | Segredo do webhook Telegram |
| Telegram e webhook | `WATCHER_TELEGRAM_ADMIN_MINI_APP_URL` | vazio | URL opcional da Mini App |
| Telegram e webhook | `WATCHER_TELEGRAM_ADMIN_USER_ID` | vazio | ID Telegram do administrador autorizado |
| Telegram e webhook | `WATCHER_EVOLUTION_WEBHOOK_TOKEN` | configurar | Token Bearer do webhook Evolution recebido no Spring |
| Telegram e webhook | `WATCHER_EVOLUTION_INSTANCE` | `watcher-evolution` | Instância Evolution aceita pelo watcher |
| RabbitMQ | `WATCHER_RABBITMQ_ENABLED` | `true` | Ativa RabbitMQ no Spring; o Compose de produção fixa true |
| RabbitMQ | `RABBITMQ_IMAGE` | ver `.env.example` (imagem fixada) | Imagem RabbitMQ pinada por digest |
| RabbitMQ | `RABBITMQ_USERNAME` | `watcher` | Usuário AMQP |
| RabbitMQ | `RABBITMQ_PASSWORD` | configurar | Senha AMQP |
| RabbitMQ | `RABBITMQ_VHOST` | `watcher` | Virtual host AMQP |
| Shadow e automação | `WATCHER_SIMULATION_ENABLED` | `true` | Habilita avaliação Shadow |
| Shadow e automação | `WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED` | `false` | Opt-in do produtor de decisões reais |
| Shadow e automação | `WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED` | `false` | Opt-in de envio real no WhatsApp |
| Shadow e automação | `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED` | `false` | Habilita transporte real Evolution |
| Shadow e automação | `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_BASE_URL` | configurar | URL HTTPS para envios pela Evolution |
| Shadow e automação | `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_INSTANCE` | `watcher-evolution` | Instância usada no envio real |
| Shadow e automação | `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_API_KEY` | configurar | Chave de envio real da Evolution |
| Shadow e automação | `WATCHER_AUTOMATION_KILL_SWITCH_ENABLED` | `true` | Bloqueia novas automações reais |
| Shadow e automação | `WATCHER_AUTOMATION_INTELLIGENT_ALERTS_ENABLED` | `false` | Opt-in de alertas inteligentes |
| Shadow e automação | `WATCHER_AUTOMATION_AUTO_REPLY_ENABLED` | `false` | Opt-in de respostas automáticas |
| Inteligência artificial | `WATCHER_AI_CLASSIFICATION_ENABLED` | `false` | Opt-in do classificador |
| Inteligência artificial | `WATCHER_AI_DISABLE_LOCAL_BUDGET` | `false` | Desativa reservas e limites locais de custo |
| Inteligência artificial | `WATCHER_AI_REQUIRE_CHAT_CONSENT` | `true` | Exige consentimento por conversa |
| Inteligência artificial | `WATCHER_AI_ALLOW_UNBUDGETED_GEMINI_FALLBACK` | `false` | Permite fallback Gemini sem orçamento local |
| Inteligência artificial | `WATCHER_AI_TRIAGE_LEGACY_FILTER_ENABLED` | `false` | Habilita filtro de triagem legado |
| Inteligência artificial | `WATCHER_AI_ENABLED` | `false` | Chave geral de IA |
| Inteligência artificial | `WATCHER_AI_GEMINIAPIKEY` | vazio | Chave Gemini para fallback técnico |
| Inteligência artificial | `WATCHER_AI_GEMINIMODEL` | vazio | Modelo Gemini |
| Inteligência artificial | `WATCHER_AI_OPENROUTERAPIKEY` | vazio | Chave OpenRouter (primário) |
| Inteligência artificial | `WATCHER_AI_OPENROUTERMODEL` | vazio | Modelo OpenRouter |
| Inteligência artificial | `WATCHER_AI_OPENROUTERMAXTOKENS` | `300` | Limite de tokens de saída incluindo reasoning |
| Inteligência artificial | `WATCHER_AI_OPENROUTERREASONINGENABLED` | `false` | Ativa reasoning se a rota suportar |
| Inteligência artificial | `WATCHER_AI_ATTEMPTHOLDMICROS` | `0` | Reserva local por tentativa em microunidades |
| Inteligência artificial | `WATCHER_AI_TIMEOUT` | `15s` | Timeout do provedor |
| Inteligência artificial | `WATCHER_AI_CONTEXT_WINDOW_HOURS` | `24` | Horas de contexto retido |
| Evolution API | `EVOLUTION_API_KEY` | configurar | Chave global Evolution |
| Evolution API | `EVOLUTION_SERVER_URL` | `http://evolution-api:8080` | URL declarada da Evolution |
| Evolution API | `EVOLUTION_IMAGE` | ver `.env.example` (imagem fixada) | Imagem Evolution pinada por digest |
| Evolution API | `EVOLUTION_HOST_PORT` | `8080` | Porta do Manager ligada só ao loopback |
| PostgreSQL local opcional | `LOCAL_POSTGRES_IMAGE` | ver `.env.example` (imagem fixada) | Imagem dos PostgreSQL locais |
| PostgreSQL local opcional | `LOCAL_WATCHER_DB_NAME` | `watcher` | Nome do banco local watcher |
| PostgreSQL local opcional | `LOCAL_WATCHER_DB_USER` | `watcher_local` | Usuário do banco local watcher |
| PostgreSQL local opcional | `LOCAL_WATCHER_DB_PASSWORD` | configurar | Senha do banco local watcher |
| PostgreSQL local opcional | `LOCAL_EVOLUTION_DB_NAME` | `evolution` | Nome do banco local Evolution |
| PostgreSQL local opcional | `LOCAL_EVOLUTION_DB_USER` | `evolution_local` | Usuário do banco local Evolution |
| PostgreSQL local opcional | `LOCAL_EVOLUTION_DB_PASSWORD` | configurar | Senha do banco local Evolution |
| Notificações | `WATCHER_NOTIFICATION_DAYS` | `1,2,3,4,5` | Dias elegíveis para horário de trabalho |
| Notificações | `WATCHER_NOTIFICATION_WINDOWS_0_START` | `08:00` | Início da janela 1 |
| Notificações | `WATCHER_NOTIFICATION_WINDOWS_0_END` | `12:00` | Fim da janela 1 |
| Notificações | `WATCHER_NOTIFICATION_WINDOWS_1_START` | `13:30` | Início da janela 2 |
| Notificações | `WATCHER_NOTIFICATION_WINDOWS_1_END` | `17:30` | Fim da janela 2 |
| Notificações | `WATCHER_ALLOWED_NAME_REGEX` | `(^\|[^A-Z0-9])JC([^A-Z0-9]\|$)` | Regex de reconhecimento de contatos de trabalho |
| Notificações | `WATCHER_INCLUDE_GROUPS` | `true` | Permite grupos arquivados elegíveis |
| Notificações | `WATCHER_NEVER_NOTIFY_GROUP_NAMES_0` | `233/234` | Nome de grupo excluído de notificações |
| Notificações | `WATCHER_MAX_BATCH_SIZE` | `50` | Tamanho máximo do lote |
| Notificações | `WATCHER_RETENTION_DAYS` | `7` | Retenção local de conteúdo sensível em dias |
| RabbitMQ | `WATCHER_RABBITMQ_EXCHANGE` | `watcher.events` | Exchange usado pelo Spring (opção avançada) |
| RabbitMQ | `WATCHER_RABBITMQ_CONFIRM_TIMEOUT` | `5s` | Timeout de publisher confirm (opção avançada) |


No Compose de produção `WATCHER_RABBITMQ_ENABLED` é **fixado em `true`**: mudar apenas esse item no `.env` não desliga o broker. A classificação necessita, entre outros controles, `WATCHER_AI_ENABLED=true` e `WATCHER_AI_CLASSIFICATION_ENABLED=true`, provedor configurado, autorização e kill switch liberado. Valores padrão conservam IA e envios reais desligados.

## Tecnologias e decisões

| Tecnologia | Por que está aqui |
|---|---|
| **Java 25 + Spring Boot 4** | Runtime único, webhooks, workers e configuração tipada com manutenção previsível. |
| **Evolution API 2.3.7** | Conexão com WhatsApp pessoal, sessões e webhooks; substituiu Node/Baileys. Não é API oficial. |
| **RabbitMQ 4.4** | Separação entre entrada HTTP e tarefas, filas persistentes, confirmação de publicação, retries e DLQ. |
| **PostgreSQL 17 + Spring Data JDBC/JDBC** | Deduplicação, estado, transações, outbox, bloqueios e queries PostgreSQL com controle explícito. |
| **Flyway** | Migrações de schema versionadas e repetíveis sem atualização manual. |
| **Telegram Bot API / java-telegram-bot-api** | Notificações e administração privada por polling ou webhook seguro. |
| **OpenRouter + Gemini opcional** | Classificação estruturada e fallback técnico, respeitando consentimento, orçamento e privacidade. |
| **Docker Compose + GHCR + GitHub Actions** | Serviços reproduzíveis e deploy automático restrito ao watcher. |
| **JUnit + Testcontainers** | Testes de banco/concorrência com PostgreSQL real e sem atingir WhatsApp. |
| **Actuator, Micrometer e Prometheus** | Saúde e métricas operacionais sem rotular conteúdo de conversas. |

## Limitações de RabbitMQ e Evolution API

**Por que a Evolution não publica diretamente no RabbitMQ?** Na **Evolution 2.3.7**, o publisher AMQP nativo usa um canal sem *publisher confirms*. Esse caminho não oferece as garantias exigidas pelo projeto. Por isso, o Compose configura **`RABBITMQ_ENABLED=false` na Evolution** e envia eventos ao **webhook autenticado do Spring**, que publica no RabbitMQ com confirmação e checagem de roteamento antes de responder HTTP com sucesso. O **`WATCHER_RABBITMQ_ENABLED=true` do Spring é outra configuração**. Veja a [documentação oficial de confirms do RabbitMQ](https://www.rabbitmq.com/docs/confirms).

- **Perda possível antes do broker:** a Evolution tenta entregar ao webhook até **10 vezes**, com backoff exponencial. Esgotado esse limite, não há reenvio durável indefinido; uma indisponibilidade prolongada pode perder o evento.
- **At-least-once não é exactly-once:** publisher confirm confirma aceitação pelo RabbitMQ, não processamento final nem entrega ao Telegram. Deduplicação e outbox reduzem riscos, mas falhas/acknowledgments ambíguos ainda podem gerar notificações duplicadas.
- **Broker único:** o RabbitMQ deste Compose tem volume persistente, **não** alta disponibilidade contra perda do disco/VPS. Inclua `watcher_rabbitmq_data` em backups e testes de recuperação.
- **Mensagem de saída:** HTTP 2xx da Evolution significa aceitação pela API, não entrega comprovada no WhatsApp. Um resultado incerto permanece `UNKNOWN` e não gera reenvio automático.
- **Identidade imperfeita:** `pushName` não diferencia sempre nome de agenda e perfil; um `@lid` sem telefone verificado não permite link `wa.me` seguro. Veja [nomes de contatos](docs/evolution-contact-names.md).
- **Sessão e privacidade:** conectividade WhatsApp não oficial pode falhar ou exigir novo QR Code. O Compose reduz logs da Evolution para `ERROR,WARN` porque categorias `LOG`/`WEBHOOKS` podem imprimir payloads sensíveis.

As limitações e procedimentos de resposta estão detalhados na [referência operacional](docs/operations/technical-reference.md#rabbitmq-e-entrega).

## Licenças

**[MIT License](LICENSE)**, Copyright (c) 2026 Hugo Alves. A licença deste repositório não altera licenças de componentes e imagens de terceiros (Evolution API, RabbitMQ, PostgreSQL, Spring e bibliotecas). Consulte os respectivos projetos antes de distribuir builds e imagens. O Hugo do ZAP não é afiliado ao WhatsApp/Meta ou Telegram.

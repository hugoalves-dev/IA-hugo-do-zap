# WhatsApp archived watcher

Serviço Spring Boot 4 / Java 25 que recebe eventos da Evolution API, grava-os em PostgreSQL e envia notificações diretamente pela Telegram Bot API. A Evolution API usa outro banco e uma chave própria. A Evolution utiliza cache local (sem container Redis) e mantém os dados persistentes no PostgreSQL e no volume de instâncias. A API Evolution só publica a porta do host em `127.0.0.1`; use túnel SSH autenticado para abrir o Manager.

No fluxo VPS, `COMPOSE_PROFILES=prod` ativa apenas `watcher` (imagem `ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest`) e `evolution-api`. O Compose usa o PostgreSQL já existente pela rede externa `infra_network`. Crie bancos e roles distintos para Spring e Evolution antes de uma implantação autorizada. A URL PostgreSQL da Evolution deve usar percent-encoding em senhas com caracteres reservados. Um profile `local-db` opcional fornece dois PostgreSQL isolados para desenvolvimento local; ele não substitui nem altera o PostgreSQL da VPS.

O TypeScript legado permanece como referência e opção de rollback até o cutover ser validado. Ele implementa o watcher anterior baseado em Baileys; o Compose atual não compila nem inicia esse código. O runtime implantado é o serviço Spring Boot Java. Não remova o legado antes de validar o cutover e encerrar a janela de rollback.

## Preparação operacional

1. Copie `.env.example` para `.env` (incluindo `COMPOSE_PROFILES=prod`), substitua cada valor `replace-*` e mantenha `.env` fora do Git. Um `.env` antigo não recebe automaticamente o novo profile. Não reutilize usuário, banco ou senha entre Spring e Evolution.
2. Confirme que a rede Docker externa `infra_network` e os dois bancos PostgreSQL já existem.
3. A imagem Evolution está fixada em `v2.3.7` e digest. Atualizações devem validar eventos e compatibilidade antes de trocar ambos. O cache local é por processo e não é compartilhado entre réplicas; este Compose executa apenas uma Evolution API.
4. Configure o webhook por instância na Evolution para `http://watcher:8080/internal/evolution/webhook`, com o cabeçalho `Authorization: Bearer <WATCHER_EVOLUTION_WEBHOOK_TOKEN>` e o mesmo identificador de instância de `WATCHER_EVOLUTION_INSTANCE`. Não habilite webhook global. A chave de API `EVOLUTION_API_KEY` é distinta do token do webhook.
5. O serviço não cria sessão nem pareia automaticamente. Pareamento, validação do payload real, cutover e envio de notificações são passos operacionais separados e exigem autorização explícita.

### PostgreSQL local opcional

O profile local usa a mesma imagem oficial `postgres:17.11-alpine3.23` fixada por digest, com volumes, bancos e roles distintos para Spring e Evolution. Não publica portas PostgreSQL. Para não gravar senhas locais no arquivo de exemplo, faça uma cópia privada de `.env.example` chamada `.env.local`, substitua todos os placeholders e não a versione. Use senhas diferentes, geradas por um gerenciador de senhas, e sem caracteres que precisem de percent-encoding na URL.

Na cópia `.env.local`, configure as URLs e os dados locais para apontarem a serviços e roles separados:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-local-watcher:5432/watcher
SPRING_DATASOURCE_USERNAME=watcher_local
SPRING_DATASOURCE_PASSWORD=<mesma senha local do role watcher_local>
EVOLUTION_DATABASE_URL=postgresql://evolution_local:<senha-local-url-encoded>@postgres-local-evolution:5432/evolution
```

Crie a rede externa exigida pelo Compose e inicie os dois bancos. Aguarde ambos ficarem `healthy` antes de iniciar a aplicação:

```sh
docker network inspect infra_network >/dev/null 2>&1 || docker network create infra_network >/dev/null
docker compose --env-file .env.local --profile local-db up -d postgres-local-watcher postgres-local-evolution
docker compose --env-file .env.local --profile local-db ps
docker compose --env-file .env.local up -d watcher evolution-api
```

O profile pode ser validado sem iniciar containers com `docker compose --env-file .env.local --profile local-db config --quiet`. Na VPS, continue usando somente as URLs PostgreSQL externas, as roles dedicadas existentes e o deploy controlado descrito abaixo. Não passe `--profile local-db` na VPS.

## Conectar WhatsApp pessoal à Evolution

O pareamento é sempre manual. O Compose inicia a API com cache local, mas não cria instância, não pede QR Code e não conecta a conta. A imagem fixada `evoapicloud/evolution-api:v2.3.7` inclui o Evolution Manager em `/manager`; a documentação oficial descreve `POST /instance/create` para criar instância e `GET /instance/connect/{instance}` para conectar e gerar QR Code ([criar instância](https://doc.evolution-api.com/v2/api-reference/instance-controller/create-instance-basic), [conectar](https://doc.evolution-api.com/v2/api-reference/instance-controller/instance-connect)). A versão da imagem está registrada no [release oficial v2.3.7](https://github.com/evolution-foundation/evolution-api/releases/tag/v2.3.7).

1. Antes de trocar de cliente, revise a sessão TypeScript legada e planeje o cutover. Parear outro cliente pode desconectar uma sessão existente ou exigir novo vínculo. O estado de autenticação legado não é migrado pelo Compose; não copie arquivos de sessão entre Baileys e Evolution. Mantenha o rollback possível até validar a conexão e os eventos recebidos.
2. Configure uma `EVOLUTION_API_KEY` forte e exclusiva no `.env` local. Inicie `docker compose --env-file .env up -d evolution-api`. As flags de envio real do watcher continuam `false`.
3. Se a API estiver na VPS, abra um túnel SSH autenticado a partir da sua máquina: `ssh -N -L 8080:127.0.0.1:8080 usuario@host-vps` (inclua `-p PORTA` se necessário). A porta pública não deve ser aberta no firewall nem publicada no endereço da VPS. Em ambiente local, use o mesmo endereço sem túnel.
4. Abra `http://localhost:8080/manager`, autentique com a chave global `EVOLUTION_API_KEY` e crie uma instância chamada `watcher-evolution` com integração `WHATSAPP-BAILEYS`. Gere uma chave própria da instância se o Manager solicitar. Não reutilize a chave global como token da instância.
5. No Manager, solicite a conexão (`GET /instance/connect/watcher-evolution`) e exiba o QR Code recém-gerado. No telefone, abra **WhatsApp → Aparelhos conectados → Conectar aparelho** (Android) ou **WhatsApp → Configurações → Aparelhos conectados → Conectar aparelho** (iPhone) e escaneie o QR Code. QR Codes expiram; gere outro pelo Manager se necessário. Nunca publique, envie em chat ou registre captura do QR Code: ele autoriza vínculo da conta.
6. Confirme no Manager que o estado da instância é `open`. Depois configure o webhook da instância para `http://watcher:8080/internal/evolution/webhook`, com o evento necessário e o token de webhook separado `WATCHER_EVOLUTION_WEBHOOK_TOKEN`. Configure apenas eventos necessários; não habilite webhook global.
7. Mantenha os três opt-ins de envio real desligados durante validação. Conectar uma conta não autoriza envio automático nem conclui o cutover do watcher.

O Manager e a API só ficam acessíveis localmente em `127.0.0.1:8080`. Em VPS, feche a porta no firewall e use o túnel SSH acima. Não use proxy público sem autenticação forte e TLS, e nunca exponha o QR Code.

Configure `WATCHER_TELEGRAM_BOT_TOKEN` com o token fornecido pelo BotFather e `WATCHER_TELEGRAM_CHAT_ID` com o ID da conversa privada autorizada. Mantenha o token somente no ambiente de execução; ele não entra no PostgreSQL nem nos logs. O bot envia resumos legíveis de mensagens arquivadas e alertas de perda de conexão.

### Comandos administrativos no Telegram

`/ultimas [N]` lista até 10 decisões retidas (padrão 5), e `/decisao <id>` consulta o identificador mostrado. Se o prefixo for ambíguo, informe mais caracteres. O acesso exige o administrador e o chat privado configurados; as respostas usam o outbox e sua limitação de entrega. As consultas não reprocessam mensagens, não chamam provedores e não mudam gates. Dados expirados ou removidos não estão disponíveis. A prévia usa somente template atualmente habilitado, identificado como automático e autorizado para a conversa. Uma prévia hipotética não é uma decisão nem uma autorização de envio. O modo e a autorização real apresentados são registros históricos, sujeitos aos gates atuais. Nenhuma mensagem bruta é exibida.

A urgência mede prazo e consequências: **LOW** para pedidos sem prazo iminente ou explicitamente não urgentes; **NORMAL** para demanda ordinária sem consequências imediatas; **HIGH** para prazo próximo, como necessidade ainda hoje com consequência relevante; **CRITICAL** para emergência imediata com dano grave; **UNCERTAIN** quando os dados são insuficientes. Exemplos de avaliação: “importante, responda quando estiver disponível” e “sem pressa” são LOW; “quando puder nesta semana” pode ser NORMAL; “preciso ainda hoje para evitar perder o prazo” é HIGH; “preciso de socorro agora, alguém está sem respirar” é CRITICAL. A palavra “importante” isolada não aumenta a urgência; “hoje” sem consequência relevante não implica emergência. Esses exemplos são metas de classificação, não garantias sobre respostas de um modelo. A confiança informada não é uma probabilidade estatisticamente calibrada. O relatório diário registra distribuição e contagem de classificações incertas, sem texto pessoal.


O polling vem desligado e não afeta as notificações. O destino atual é uma conversa privada: IDs de grupo não são aceitos pela configuração. O User ID identifica a pessoa autorizada; não é o token do bot e pode ser diferente do Chat ID. O bot exige ambos os IDs e tipo `private` para aceitar comandos e confirmações.

Na VPS, obtenha o User ID e o Chat ID por um método confiável, sem divulgar o token. Edite o arquivo privado com `vim .env`:

```dotenv
WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED=true
WATCHER_TELEGRAM_ADMIN_USER_ID=123456789
```

Substitua o exemplo pelo User ID real e mantenha `WATCHER_TELEGRAM_CHAT_ID` como a conversa privada correspondente. Um ID ausente, inválido ou não positivo impede a inicialização quando o polling está ligado. Antes de ativar, remova qualquer webhook e pare outros consumidores `getUpdates` do mesmo bot. O lock e offset no PostgreSQL coordenam instâncias deste watcher, não consumidores externos.

Recrie somente o watcher: `docker compose --env-file .env up -d --no-deps --force-recreate watcher`. Envie `/status` na conversa privada autorizada. Para diagnosticar, use `docker compose --env-file .env logs --tail=100 watcher`: uma falha de inicialização aponta validação de configuração; `Telegram admin polling failed` indica falha no polling ou banco; `Telegram admin update rejected` indica identidade ou chat não autorizado. Se o polling estiver desligado, não há serviço de polling nem mensagens desses diagnósticos. Não publique logs completos sem revisão.

Verifique a entrega no banco consultando somente estados, sem payload: `SELECT status, count(*) FROM outbox_actions WHERE action_type = 'telegram_admin_reply' GROUP BY status;`. `ACKNOWLEDGED` confirma aceitação pelo transporte, não leitura pelo usuário. `/emergencia` e `/recuperar` conservam suas confirmações e travas persistentes. Esta configuração não ativa IA, alertas inteligentes nem envio real de WhatsApp.

## Modos de simulação e ações reais

Por padrão, `WATCHER_SIMULATION_ENABLED=true` permite o modo shadow. Para pausá-lo globalmente, defina `WATCHER_SIMULATION_ENABLED=false`. A captura e o agendador ficam desligados quando o produtor real também está desligado. Com `WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=true`, a avaliação real continua mesmo com shadow desligado. O administrador pode pausar avaliações shadow com `/simulacao off` e remover a pausa com `/simulacao on` (confirmado); o ambiente continua sendo a fonte de habilitação e a pausa não bloqueia o produtor real. Com shadow e produtor real desligados, não há captura, avaliação nem novas ações.

Envio real exige opt-in explícito nas três variáveis abaixo. Todas começam em `false`, e as credenciais Evolution só são validadas quando os três opt-ins estão ligados:

```dotenv
WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_BASE_URL=https://evolution.example.invalid
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_INSTANCE=watcher-evolution
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_API_KEY=replace-with-evolution-api-key
```

Para ativar, ajuste explicitamente os três flags para `true`, informe URL HTTPS e credenciais válidas, e reinicie o serviço. Isso não ignora decisão elegível, consentimento/autorização explícita da conversa, template aprovado, política corrente nem o kill switch do ambiente e a trava persistente de emergência. Shadow e envio real são independentes: desligar shadow não ativa envio real, e ligar shadow não ativa envio real.

Uma resposta HTTP 2xx da Evolution significa apenas que a API aceitou a requisição. Sem recibo autenticado de entrega, o resultado permanece `UNKNOWN`, não há reenvio automático, e o estado não deve ser tratado como confirmação de entrega.

## Provedores de IA e limites operacionais

As credenciais ficam no `.env` privado da VPS ou na cópia `.env.local`; nunca as grave no repositório, em tickets ou em mensagens. Crie uma chave Gemini no [Google AI Studio](https://ai.google.dev/gemini-api/docs/api-key) ou uma chave OpenRouter em [Settings → Keys](https://openrouter.ai/settings/keys). O Compose passa as propriedades existentes de `watcher.ai` ao Spring. Os nomes `WATCHER_AI_GEMINIAPIKEY` e `WATCHER_AI_OPENROUTERAPIKEY` parecem concatenados porque seguem a regra de binding de variáveis de ambiente do [Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding).

```dotenv
WATCHER_AI_ENABLED=false
WATCHER_AI_CLASSIFICATION_ENABLED=false
WATCHER_AI_DISABLE_LOCAL_BUDGET=false
WATCHER_AI_REQUIRE_CHAT_CONSENT=true
WATCHER_AI_ALLOW_UNBUDGETED_GEMINI_FALLBACK=false
WATCHER_AUTOMATION_KILL_SWITCH_ENABLED=true
WATCHER_AUTOMATION_INTELLIGENT_ALERTS_ENABLED=false
WATCHER_AUTOMATION_AUTO_REPLY_ENABLED=false
WATCHER_AI_GEMINIAPIKEY=
WATCHER_AI_GEMINIMODEL=
WATCHER_AI_OPENROUTERAPIKEY=
WATCHER_AI_OPENROUTERMODEL=
WATCHER_AI_ATTEMPTHOLDMICROS=0
WATCHER_AI_TIMEOUT=15s
WATCHER_AI_CONTEXT_WINDOW_HOURS=24
```

A classificação exige `WATCHER_AI_ENABLED=true` e `WATCHER_AI_CLASSIFICATION_ENABLED=true`, além de `WATCHER_AUTOMATION_KILL_SWITCH_ENABLED=false`, provedor válido e modo de processamento ativo. Os defaults são seguros: classificação desligada, kill switch ligado, alertas e respostas desligados. As quatro colunas antigas de `automation_settings` e `telegram_admin_settings.simulation_enabled` são preservadas como dados legados, mas não configuram esses comportamentos; não as altere para ativação. Mudanças no `.env` exigem recriar o watcher.

Por padrão, orçamento local e consentimento por conversa são obrigatórios. `WATCHER_AI_DISABLE_LOCAL_BUDGET=true` dispensa reservas e limites locais, deixando a quota para o provedor; `WATCHER_AI_REQUIRE_CHAT_CONSENT=false` dispensa os registros locais de consentimento. Eles são opt-ins separados. Com orçamento local ativo, `WATCHER_AI_ATTEMPTHOLDMICROS` deve ser positivo e cada tentativa reserva e reconcilia custos como antes. Gemini continua opcional; quando o orçamento local é dispensado, ele também exige `WATCHER_AI_ALLOW_UNBUDGETED_GEMINI_FALLBACK=true`. A quota OpenRouter não cobre a conta Gemini.

O classificador recebe a mensagem-alvo e até cinco mensagens anteriores retidas, identificadas como contato ou proprietário. `WATCHER_AI_CONTEXT_WINDOW_HOURS` limita a janela a 24 horas por padrão; valores válidos vão de 1 a 168 horas. A retenção sensível continua limitada a sete dias. Mensagens próprias de grupos não são usadas quando não há vínculo confiável com o participante. O sistema não busca histórico remoto nem reclassifica decisões anteriores.

O endpoint `/actuator/prometheus` exporta métricas na porta interna do watcher. Para uma implantação autorizada, conecte Prometheus à mesma rede Docker e configure o target `watcher:8080` com `metrics_path: /actuator/prometheus`. Não publique o endpoint no proxy nem no host. Confira o target na interface de targets do Prometheus e valide a consulta `up{job="watcher"}`; a configuração do scrape e o deploy não são aplicados automaticamente por este repositório. As métricas de classificação, decisões e chamadas usam apenas rótulos limitados. Custos no ledger local podem ser zero quando a reserva local é dispensada; isso não demonstra custo externo zero. Tokens e custo reportados pelo provedor só são contados quando presentes na resposta.

Os painéis Grafana existentes **GenAI Observability** e **Lightweight APM for OpenTelemetry** já exibem chamadas, tokens, custos e latência do OpenRouter por meio do Tempo. Eles não mostram falhas que acontecem antes da chamada; use as métricas `watcher_shadow_*` e os logs `shadow_decision` do watcher para esses casos. O UUID opaco `batch_id` no log e nos metadados `trace` do OpenRouter permite correlacionar uma decisão com a chamada; não é rótulo de métrica. As consultas OpenRouter agregam outras aplicações que usem a mesma origem; não interprete seus totais como custo exclusivo do watcher sem filtrar a correlação.

No OpenRouter, ative Privacy Mode em cada destination Broadcast para Tempo. Verifique um trace novo e seus campos de entrada, saída e metadados sem compartilhar conteúdo. A alteração não remove traces antigos; revise acesso e retenção desses dados separadamente.

Os avisos históricos `Telegram delivery rejected: errorCode=404` de 29/10/2026 foram gerados pelo transporte Telegram: oito ocorrências entre 16:53 e 17:18 no horário de São Paulo. Os logs consultados não identificam a causa do 404; eles não demonstram falha do classificador. Confira a configuração do bot e o destino sem divulgar token ou identificador, e compare com entregas posteriores antes de alterar o transporte.

Com consentimento por chat dispensado e escopo `ALL`, texto minimizado de qualquer conversa elegível pode ser enviado ao provedor externo. Isso não equivale ao consentimento dos participantes. Restrinja o escopo e minimize dados conforme necessário. Limites no provedor podem diferir do ledger local por atrasos e regras de faturamento. Veja o [checklist de ativação](docs/local-ai-activation-checklist.md).

O administrador autorizado pode executar `/emergencia` para bloquear imediatamente novas chamadas, alertas e respostas. A trava sobrevive ao reinício e a mudanças no `.env`. Para recuperar, use `/recuperar` no chat privado autorizado e confirme com o token fornecido; isso não altera os controles do ambiente. Uma requisição já enviada não pode ser recolhida, mas seu resultado é revalidado antes de persistir a decisão. `/status` mostra controles efetivos e a emergência. Shadow nunca autoriza envios reais.

O cliente usa [`java-telegram-bot-api` 10.3.0](https://github.com/pengrad/java-telegram-bot-api), projeto comunitário listado na [documentação do Telegram](https://core.telegram.org/bots/samples), sob licença Apache-2.0.

O container Spring não publica porta no host. Spring e Evolution compartilham a rede externa `infra_network`; a Evolution usa cache local (`CACHE_LOCAL_ENABLED=true`, `CACHE_REDIS_ENABLED=false`), sem Redis neste Compose. Os probes de liveness/readiness ficam em `/actuator/health/liveness` e `/actuator/health/readiness`; a readiness também verifica PostgreSQL. O healthcheck Docker consulta readiness e oculta detalhes. Mensagens e histórico não são persistidos pela configuração da Evolution (`DATABASE_SAVE_DATA_NEW_MESSAGE=false`, `DATABASE_SAVE_MESSAGE_UPDATE=false` e `DATABASE_SAVE_DATA_HISTORIC=false`).

Depois da preparação e autorização operacional, o Compose pode ser validado sem iniciar containers:

```sh
docker compose --env-file .env config --quiet
docker compose --env-file .env config --services # evolution-api e watcher
```

Uma execução local autorizada pode usar `docker compose --env-file .env up -d`. Para construir a imagem localmente em vez de baixar a publicada, use `docker build -t ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest .` antes. Para implantação na VPS, siga o fluxo controlado abaixo.

## CI/CD e deploy automático

O deploy segue o padrão do Applywell: **push em `main` → `mvn verify` (Java 25) → imagem GHCR por digest → atualização automática do watcher por SSH → healthcheck**. Pull requests apenas executam testes. O botão *Run workflow* é opcional para reexecutar o pipeline, não é necessário para implantar uma alteração.

O deploy usa os secrets `SSH_KEY`, `SSH_PORT`, `VPS_IP` e `VPS_USER`; `VPS_DEPLOY_DIR` é opcional e vale `/docker/ia-hugo-do-zap`. Não grava tokens ou senhas no repositório. A conta SSH precisa ter acesso ao diretório de deploy e ao Docker. As imagens são publicadas como `latest` e `sha-<commit>`, mas a implantação é fixada ao digest imutável produzido pelo workflow.

A ação de deploy altera **apenas** o serviço `watcher` usando `docker compose up -d --no-deps --wait --wait-timeout 240 watcher`. Nunca executa `down`, `--remove-orphans`, prune, migrações SQL manuais nem reinicia Evolution. Arquivos `.env`, banco PostgreSQL, volumes e instâncias existentes são preservados. Os envios reais de WhatsApp continuam sujeitos às flags de opt-in, que o deploy não modifica.

Após um deploy bem-sucedido, `compose.override.yaml` contém a imagem por digest, e `.watcher-deployed-compose.sha256` registra a versão aprovada do `compose.yaml`. Futuras alterações ao Compose no GitHub também são sincronizadas automaticamente, desde que o arquivo da VPS não tenha sido modificado fora do workflow. Se o healthcheck falhar, a configuração anterior é restaurada e, quando havia um watcher saudável, o serviço é reiniciado com a imagem anterior. O workflow falha e registra o erro, sem fingir sucesso.

**Recuperação do bootstrap antigo:** a primeira execução automática reconhece `.watcher-bootstrap.journal` e `.compose.override.yaml.candidate` de uma implantação inicial anterior. Verifica formato, projeto e referência imutável antes de prosseguir. Somente após um watcher saudável move os arquivos legados para uma pasta privada `.watcher-legacy-recovered.*`, sem apagá-los. Journals anteriores inválidos ou incompletos são bloqueados para revisão, não descartados. O novo fluxo não cria journals persistentes que bloqueiem a próxima atualização.

**Instalação inicial:** o diretório `/docker/ia-hugo-do-zap` já precisa ter `.env` e `compose.yaml`; o PostgreSQL externo, as roles dedicadas e a rede `infra_network` devem existir. A primeira implantação exige que o `compose.yaml` da VPS corresponda ao repositório (para não sobrescrever configurações desconhecidas). A partir do primeiro sucesso, o workflow gerencia atualizações futuras do Compose de forma automática e detecta alterações manuais concorrentes. Para inspecionar o watcher: `cd /docker/ia-hugo-do-zap && docker compose ps watcher && docker compose logs --tail=100 watcher`.

A conexão SSH continua autenticada exclusivamente por `SSH_KEY`; esta implementação não acrescenta verificação explícita do fingerprint do host, conforme a configuração anterior. Para endurecer a segurança posteriormente, valide o fingerprint conhecido da VPS.

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

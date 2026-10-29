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

## Modos de simulação e ações reais

Por padrão, `WATCHER_SIMULATION_ENABLED=true` permite o modo shadow. Para pausá-lo globalmente, defina `WATCHER_SIMULATION_ENABLED=false`. A captura e o agendador ficam desligados quando o produtor real também está desligado. Com `WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=true`, a avaliação real continua mesmo com shadow desligado. O administrador pode pausar avaliações shadow por `telegram_admin_settings.simulation_enabled`; esse ajuste não bloqueia o produtor real. Com shadow e produtor real desligados, não há captura, avaliação nem novas ações.

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

## Provedores de IA e limites operacionais

As credenciais ficam no `.env` privado da VPS ou na cópia `.env.local`; nunca as grave no repositório, em tickets ou em mensagens. Crie uma chave Gemini no [Google AI Studio](https://ai.google.dev/gemini-api/docs/api-key) ou uma chave OpenRouter em [Settings → Keys](https://openrouter.ai/settings/keys). O Compose passa as propriedades existentes de `watcher.ai` ao Spring. Os nomes `WATCHER_AI_GEMINIAPIKEY` e `WATCHER_AI_OPENROUTERAPIKEY` parecem concatenados porque seguem a regra de binding de variáveis de ambiente do [Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding).

```dotenv
WATCHER_AI_ENABLED=false
WATCHER_AI_GEMINIAPIKEY=
WATCHER_AI_GEMINIMODEL=
WATCHER_AI_OPENROUTERAPIKEY=
WATCHER_AI_OPENROUTERMODEL=
WATCHER_AI_ATTEMPTHOLDMICROS=0
WATCHER_AI_TIMEOUT=15s
```

`WATCHER_AI_ENABLED` é o único flag de configuração para os provedores e fica `false` por padrão. Para habilitar a configuração de IA, seria necessário ligá-lo, preencher uma chave e um identificador de modelo Gemini, configurar `WATCHER_AI_ATTEMPTHOLDMICROS` com valor positivo e manter o timeout maior que zero e no máximo 30 segundos. Guarde somente chaves reais no `.env` privado e reinicie o container para aplicar mudanças.

Gemini é sempre o provedor primário. OpenRouter só é construído como fallback quando sua chave e modelo estão preenchidos, e só é chamado após uma falha técnica do Gemini. Não há flag independente para promover OpenRouter a primário; remover qualquer uma das duas configurações OpenRouter desativa esse fallback. Deixar Gemini sem chave/modelo torna a configuração inteira indisponível, mesmo se OpenRouter estiver preenchido.

Credenciais e esse flag, sozinhos, **não habilitam chamadas**. O pipeline também exige modo shadow ativo, `automation_settings.ai_classification_enabled=true`, consentimentos atuais na tabela `ai_data_consents` para o chat, provedor e propósito aplicáveis, e limites diários e mensais registrados em `ai_budget_limits`. As migrações deixam o gate de classificação desligado, não criam consentimentos e não inserem limites. A tabela de consentimento exige `privacy_approved=true` e `expires_at` futuro; os propósitos reconhecidos são `CONTACT_REQUEST`, `GROUP_TRIGGER` e `CRITICAL_URGENCY`.

O comando administrativo `/orcamento <diário-micros> <mensal-micros>` consulta ou atualiza os limites em micros de USD; alterações pedem `/confirm <token>`. Uma ausência de limite bloqueia chamadas pagas. Não há atualmente comando ou tela suportados para ligar `ai_classification_enabled` nem para conceder/revogar consentimentos por provedor. Por isso, o repositório ainda não oferece um procedimento operacional suportado para ativar IA paga de ponta a ponta; não trate os exemplos de configuração acima como autorização para uso.

Para desligar as chamadas de IA via configuração, mantenha `WATCHER_AI_ENABLED=false` e reinicie o watcher. O comando `/emergencia` também desliga o gate de classificação persistido e ativa o kill switch, mas desliga junto alertas e respostas reais; não existe comando para reativar o gate de IA depois disso. Modo shadow, uso de IA e envio real de WhatsApp são controles distintos: IA não autoriza nem dispara envio real.

O cliente usa [`java-telegram-bot-api` 10.3.0](https://github.com/pengrad/java-telegram-bot-api), projeto comunitário listado na [documentação do Telegram](https://core.telegram.org/bots/samples), sob licença Apache-2.0.

O container Spring não publica porta no host. Spring e Evolution compartilham a rede externa `infra_network`; a Evolution usa cache local (`CACHE_LOCAL_ENABLED=true`, `CACHE_REDIS_ENABLED=false`), sem Redis neste Compose. Os probes de liveness/readiness ficam em `/actuator/health/liveness` e `/actuator/health/readiness`; a readiness também verifica PostgreSQL. O healthcheck Docker consulta readiness e oculta detalhes. Mensagens e histórico não são persistidos pela configuração da Evolution (`DATABASE_SAVE_DATA_NEW_MESSAGE=false`, `DATABASE_SAVE_MESSAGE_UPDATE=false` e `DATABASE_SAVE_DATA_HISTORIC=false`).

Depois da preparação e autorização operacional, o Compose pode ser validado sem iniciar containers:

```sh
docker compose --env-file .env config --quiet
docker compose --env-file .env config --services # evolution-api e watcher
```

Uma execução local autorizada pode usar `docker compose --env-file .env up -d`. Para construir a imagem localmente em vez de baixar a publicada, use `docker build -t ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest .` antes. Para implantação na VPS, siga o fluxo controlado abaixo.

## CI/CD e deploy controlado

`.github/workflows/backend.yml` executa `mvn verify` e os checks de CI/CD em pushes e pull requests usando Java 25. Pull requests não publicam imagem nem fazem deploy. Push em `main` publica a imagem com tag do commit e attestation de proveniência, mas não atualiza a VPS. Para operação manual, abra **Actions → Backend CI/CD → Run workflow**, selecione `main` e marque exatamente uma opção: `deploy` para uma instalação existente ou `bootstrap` para um VPS vazio. O job consome o digest da imagem produzida nessa execução. O script remoto vem do mesmo commit; antes de agir, compara o SHA-256 de `compose.yaml` na VPS com o arquivo validado pelo workflow.

Configure estes GitHub Actions repository secrets, sem valores padrão:

- `SSH_KEY`: chave privada de deploy para a conta restrita da VPS (única credencial de autenticação SSH; sem senha).
- `SSH_PORT`: porta SSH da VPS.
- `VPS_IP`: endereço usado na conexão SSH.
- `VPS_USER`: conta SSH de deploy.
A conexão usa somente `SSH_KEY` para autenticação, mas **não verifica o fingerprint da chave pública do servidor**. Esta simplificação, solicitada para o deploy, reduz a proteção contra ataques man-in-the-middle. Para recuperar essa proteção futuramente, configure a validação explícita do fingerprint da VPS usando a chave verificada pelo console do provedor.

O diretório padrão é `/docker/ia-hugo-do-zap`. Opcionalmente, configure a repository variable `VPS_DEPLOY_DIR` para sobrescrevê-lo com outro caminho absoluto, normalizado e sem segmentos `.` ou `..`. O diretório precisa existir na VPS e conter o `compose.yaml` byte a byte igual ao commit selecionado e o `.env` já configurado. O job não cria diretórios, não grava nem substitui `.env`, não remove containers órfãos e não apaga imagens ou volumes.

O workflow usa a [action oficial `appleboy/ssh-action`](https://github.com/appleboy/ssh-action), fixada por SHA de commit. O `DRONE_SSH_VERSION=1.8.1` no ambiente fixa o binário `drone-ssh` sem depender de um input não declarado pela action. `scripts/deploy-vps-action.sh` é um wrapper POSIX enviado por `script_path`; ele decodifica o conteúdo base64 de `scripts/deploy-vps.sh` para um arquivo temporário privado e chama `bash` explicitamente. O wrapper retorna o status do deploy e remove o arquivo temporário. Script, diretório e digest passam codificados, sem interpolação de shell. O deploy ativa `set -euo pipefail`; o wrapper também falha fechado se Bash ou decodificação falhar.

O digest aprovado fica em `compose.override.yaml`, separado do `.env`. O Compose carrega esse override automaticamente em comandos padrão como `docker compose up`; no diretório da VPS, não passe `-f compose.yaml` nem defina `COMPOSE_FILE` ou `COMPOSE_PROJECT_NAME`, pois esses valores podem ignorar o lock ou apontar a outro projeto. `pull_policy: never` faz os comandos padrão usarem o digest local aprovado e falharem, sem pull ou build alternativo, se ele não estiver em cache. No modo `deploy`, o operador fornece um lock para o digest saudável atual. No modo `bootstrap`, o destino não pode ter override; o workflow cria esse lock somente após o novo watcher ficar healthy.

Para `deploy` numa instalação existente, prepare `compose.override.yaml` com o digest saudável já presente no cache Docker:

```yaml
# Managed by backend deploy workflow; do not edit.
services:
  watcher:
    image: ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:<digest-atual-verificado>
    pull_policy: never
```

`bootstrap` exige um diretório, `.env` e `compose.yaml` isolados, sem serviço watcher, projeto Compose, volume do projeto, override ou journal anterior. Um lock vazio deixado por uma tentativa que falhou antes de iniciar containers pode ser reutilizado; locks ocupados/não vazios bloqueiam. O nome do diretório precisa ser um project name Docker válido e exclusivo. O job valida os três flags de envio real desligados e exige que Spring e Evolution usem o mesmo hostname/porta PostgreSQL normalizado (porta padrão `5432`), mas bancos e roles distintos. Ele rejeita query parameters, percent-encoding e caracteres não ASCII nesses identificadores, e hostnames diferentes; não faz conexão SQL de identidade. Se as URLs usam aliases DNS diferentes, o bootstrap falha fechado porque não prova que apontam ao mesmo servidor; configure o mesmo host canônico ou resolva a identidade externamente. Ele também exige a rede externa `infra_network`. O bootstrap inicia somente `watcher`, sem iniciar Evolution, e não remove recursos. Após readiness, salva o primeiro digest em `compose.override.yaml`.

Falha antes de iniciar o serviço remove apenas o journal/candidato temporários e permite novo `bootstrap`. Falha depois do start preserva o container, `.compose.override.yaml.candidate` e `.watcher-bootstrap.journal`; outras execuções ficam bloqueadas para revisão. Inspecione o estado sem remover recursos, por exemplo com `docker compose --env-file .env -p <nome-do-diretório> -f compose.yaml -f .compose.override.yaml.candidate ps watcher`. Se o candidato estiver healthy e aprovado pelo operador, promova-o e limpe o journal sob o lock:

```sh
flock -n .watcher-deploy.lock bash -euo pipefail -c 'mv .compose.override.yaml.candidate compose.override.yaml && rm .watcher-bootstrap.journal'
```

Se `.compose.override.yaml.candidate` não existir, o processo pode ter parado depois de promover o lock. Não sobrescreva `compose.override.yaml` nem `.env`. Sob o mesmo lock, compare a imagem padrão com o digest do journal, confirme container running/healthy e limpe apenas o journal:

```sh
set -euo pipefail
exec 9>>.watcher-deploy.lock
flock -n 9
test ! -e .compose.override.yaml.candidate
grep -Eq '^phase=(promoting|committed)$' .watcher-bootstrap.journal
candidate_image="$(sed -n 's/^candidate_image=//p' .watcher-bootstrap.journal)"
current_image="$(docker compose --env-file .env -p "$(basename "$PWD")" config --images watcher)"
test "$current_image" = "$candidate_image"
container_id="$(docker compose --env-file .env -p "$(basename "$PWD")" ps -q watcher)"
test -n "$container_id"
test "$(docker inspect --format '{{.State.Running}}' "$container_id")" = true
test "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")" = healthy
rm -f -- .watcher-bootstrap.journal
```

Não use `compose down`, prune ou remoção de volumes para recuperar bootstrap.

O workflow falha sem substituir overrides de operador. Para `deploy`, verifica que a imagem anterior está local antes de começar; falha ou timeout restaura esse digest com `--pull never`, sem depender do GHCR. O timeout de readiness é 180 segundos.

Deploys usam o lock exclusivo `.watcher-deploy.lock` via `flock`. Para coordenar comandos Compose manuais que alterem containers, use o mesmo lock, por exemplo `flock -n .watcher-deploy.lock docker compose up -d watcher`. O journal `.watcher-deploy.journal` guarda somente fase, SHA do Compose e digests. Se um deploy existente for interrompido, a próxima execução restaura e verifica o digest anterior, remove o journal e para com instrução para revisão operacional. O bootstrap também usa `.watcher-bootstrap.journal`; se falhar depois de iniciar o watcher, preserva o candidato e o journal, sem apagar containers, `.env` ou volumes. Inspecione com `docker compose -f compose.yaml -f .compose.override.yaml.candidate ps watcher` e os logs antes de resolver manualmente o estado. Journal inválido, Compose divergente ou digest anterior ausente bloqueiam a automação.

Antes do primeiro deploy, a VPS precisa ter Bash, `flock`, `sha256sum`, Docker Compose com `up --wait --wait-timeout`, acesso ao `infra_network`, os bancos PostgreSQL acessíveis e permissões Docker para `VPS_USER`. Se o pacote GHCR não for público, autentique previamente o Docker da VPS com permissão de leitura do pacote. Mantenha o volume `evolution_instances` e a configuração operacional existente; o workflow não copia arquivos, remove serviços/volumes, faz pareamento, cutover ou ativa flags de envio real.

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

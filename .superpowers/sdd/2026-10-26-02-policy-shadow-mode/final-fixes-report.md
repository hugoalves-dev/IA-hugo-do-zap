# Plano 2 — correções da revisão final

## Escopo

Correções dos achados P1/P2 de `review-7ebf71a..cd6c9a6.diff`. Plano 3, provedores IA, administração Telegram e envios reais não foram implementados. `.idea/` preservada. Nenhum conteúdo de `target/` foi inspecionado.

## Correções

- **Ingestão isolada:** `MessageService` não chama mais `DebounceService.enqueue`. O mesmo INSERT deduplicado de `processed_message` captura o snapshot opcional e seu prazo, atomicamente com a notificação legado. Falha no processamento shadow nunca reverte uma aceitação já confirmada. Desligado, shadow não grava payload e o scheduler continua condicionado à flag.
- **Recuperação e backpressure:** o poll drena até 100 snapshots, cada qual em transação própria. `FOR UPDATE SKIP LOCKED`, deduplicação e remoção somente após enqueue confirmado impedem dupla captura. Saturação ou erro DB reverte somente a transação shadow e conserva o snapshot para outro poll/reinício. Capturas e payloads expiram em até 7 dias; retenção apaga payloads vencidos. O backlog usa a retenção já limitada de `processed_message`; a capacidade de lotes continua configurável. Um capture bloqueado segura os seguintes no poll atual, evitando descartar eventos para aliviar pressão.
- **Políticas reais:** `PersistedPolicyRepository` carrega global, perfil e override parcial; `PolicyResolver` mantém precedência e origem. O perfil legado é fallback apenas quando não há perfil explícito por chat. `AvailabilityService` recebe timezone configurada, override temporário e `HolidayService` persistido. Auditoria grava valores/origens da policy, disponibilidade, fonte e motivo mesmo com IA desativada. `ALL` mantém elegibilidade de estado desconhecido; modos restritos continuam bloqueando UNKNOWN.
- **Decisão:** disponibilidade incompleta bloqueia com auditoria. Durante indisponibilidade, alerta inteligente exige urgência crítica e referência pessoal explícita. Autorizações e templates continuam obrigatórios. Gate real é calculado antes do cooldown simulado; cooldown simulado suprime somente `wouldAlert`/`wouldReply`, sem negar um gate real independentemente elegível. Isso registra elegibilidade, sem realizar envio.
- **Aliases:** somente o par explícito já exigido por `IdentityService` permite merge. Autorizações exatas são preservadas, cooldowns usam máximos por dimensão, lotes são reindexados, mensagens do mesmo chat/remetente/external ID são deduplicadas. IDs de lotes e auditoria são preservados. Policies explícitas divergentes entre aliases permanecem armazenadas e geram `NO_POLICY`/bloqueio auditado, sem escolher uma silenciosamente.
- **Concorrência no merge:** advisory lock transacional comum serializa workers shadow e merge explícito. Ingestão normal não adquire esse lock. Avaliação recarrega a identidade atual do lote depois do lock, para não confiar em um claim anterior ao merge. Tradeoff: trabalhadores shadow são serializados nesta implementação conservadora; não há chamadas de rede sob esse lock.

## TDD e verificação

RED observado:

1. Saturação lançava `simulation batch capacity reached` em `accept`; tabela shadow indisponível lançava erro SQL e revertia ingestão.
2. Cooldown simulado tornava `realActionAllowed=false` mesmo com cooldown real livre.
3. Auditoria de disponibilidade era NULL apesar de overrides persistidos.
4. Merge perdia autorização da identidade secundária.
5. Mensagem já lida ainda deixava captura shadow.
6. Disponibilidade incompleta e alertas comuns durante indisponibilidade não eram bloqueados.
7. Policies conflitantes de aliases não eram bloqueadas.

GREEN intermediário confirmou correções e teste com dois workers drenando um único evento. A ampliação focada revelou um teste preexistente que apagava `simulation_decisions` e contaminava testes seguintes; agora restaura a tabela em `finally`.

Comando final (Java 25, PostgreSQL 17 Testcontainers; Docker Desktop API 1.44):

```powershell
$env:JAVA_HOME='F:/graalvm-25+37-LTS-jvmci-b01'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE='/var/run/docker.sock'
mvn '-Dtest=IdentityServiceTest,SimulationServiceTest,DecisionEngineTest,MessageServiceTest,DebounceServiceTest,PolicyResolverTest,AvailabilityServiceTest,SimulationSchedulerTest,RetentionServiceTest,RetentionRegressionTest' '-DargLine=-Dapi.version=1.44' test
```

Resultado final: **BUILD SUCCESS; 72 testes, zero falhas, zero erros, zero ignorados**. PostgreSQL 17.11 real via Testcontainers; duração 52,305 s. `git diff --check` sem erros. Nenhuma suíte completa foi executada.

`CodeGraph.get_edit_context` retornou workspace com zero nodes e nenhum símbolo. Foi usado fallback imediato para buscas `rg` e leituras diretas apenas dos serviços, migrações, plano e testes afetados. RTK no caminho referenciado `C:/Users/vitor.hugo/.codex-cli-private/RTK.md` não existe neste perfil; comandos nativos preservaram evidência exata.

## Contratos obrigatórios antes do plano 3

- `SimulationService` escreve somente `last_simulation_action_at`. `last_real_action_at` é lido para o gate e **nunca avança em simulação**. O merge apenas preserva o máximo de timestamps existentes, sem usar o horário do merge como ação real.
- Plano 3 precisa atualizar o timestamp real atomicamente com entrega real confirmada; tentativa, falha e resultado incerto não significam entrega confirmada. Testar sucesso, falha, resultado incerto e concorrência/idempotência antes de habilitar ação real.
- `Classification.explicitPersonalReference` ainda é dado de entrada do engine; `SimulationService` usa `Classification.unavailable()`, sem inferir intenção da mensagem. Antes do plano 3, referência explícita deverá ser verificada deterministicamente contra menção estrutural, nome/apelido cadastrado ou resposta a mensagem de autoria comprovada. Boolean fornecido por IA e gatilho de grupo não bastam. Testes devem cobrir referência ausente, pessoa diferente, citação sem autoria comprovada e tentativa de prompt injection.
- Nenhum calendário factual foi inventado. O teste de integração usa entrada explicitamente nomeada como fixture fictícia; UNKNOWN não altera disponibilidade, e VALIDATED exige atestação manual. Sistema não verifica automaticamente a fonte publicada.
- Verificação foi local. Não comprova CI, deploy, runtime de produção, classificação IA nem entrega WhatsApp/Telegram.


## Correção load-bearing de retenção/replay após a revisão

A revisão do commit de correções encontrou dois defeitos introduzidos na transferência da captura: o poll usava seu horário como origem do lote e renovava o prazo sensível. Uma captura aguardando seis dias ganhava sete dias novos. Mensagens recebidas em janelas distintas também eram agrupadas na mesma janela do poll.

Correção restrita a `DebounceService` e seus testes:

- Transferência lê `processed_at` e `shadow_expires_at` originais. Criação do lote e metadados usa a origem persistida, sem renovação pelo poll.
- Deadline do lote é o mínimo entre seu prazo existente e o deadline das capturas incorporadas, inclusive quando a mensagem já está deduplicada no lote. Lotes com prazos diferentes expiram integralmente no menor prazo: isso pode apagar o item mais novo antes do seu prazo individual, nunca manter o item antigo além do prazo original. Não houve mudança de schema nem de `RetentionService`.
- Capturas pendentes são ordenadas pelo timestamp de recebimento, com fallback para `processed_at`, antes do replay. Lote OPEN cuja janela original já terminou vira READY antes de incorporar a próxima captura. Debounce usa recebimento original, preservando tanto 10 segundos de inatividade quanto máximo de 30 segundos, independentemente do atraso do poll ou da ordem de ingestão.
- RED PostgreSQL confirmou quatro falhas: captura antiga expirava em 03/11 em vez de 28/10; lote com deadline curto era prorrogado; backlog das marcas 0/9/18/27/31/42 segundos não reconstruía três lotes; recaptura duplicada mantinha o prazo prorrogado. Os testes verificam transferência, lote misto, deduplicação, conteúdo/ordem dos lotes e purge exatamente no prazo original.

Verificação adicional: **BUILD SUCCESS; 51 testes, zero falhas, zero erros, zero ignorados**, PostgreSQL 17.11 Testcontainers, duração 48,685 s. `git diff --check` sem erros.

```powershell
mvn '-Dtest=DebounceServiceTest,MessageServiceTest,IdentityServiceTest,SimulationServiceTest,RetentionServiceTest,RetentionRegressionTest' '-DargLine=-Dapi.version=1.44' test
```

Mesmas variáveis de Java/Docker documentadas acima. Nenhuma suíte completa, nenhum acesso ao conteúdo de `target/`, nenhuma mudança em `.idea/` e nenhum trabalho do plano 3.

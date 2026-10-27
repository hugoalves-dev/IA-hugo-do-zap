# Plano 2 — Políticas, disponibilidade e Shadow Mode

> **Para execução:** use `superpowers:executing-plans` ou `superpowers:subagent-driven-development`, com TDD e testes direcionados por tarefa.

**Objetivo:** implementar no Spring políticas configuráveis, disponibilidade, lotes e simulações auditáveis para todos os chats arquivados.

**Arquitetura:** consumir eventos e snapshots duráveis do plano 1; decisões determinísticas usam precedência global → perfil → override e produzem auditoria, sem efeitos WhatsApp em Shadow Mode.

**Stack:** Java 25, Spring Boot 4.x, PostgreSQL/Flyway, JDBC e Testcontainers.

**Spec:** `docs/superpower/spec/2026-10-16-archived-watcher-automation-design.md`, alterada pela rejeição da POC como etapa prévia. **Pré-requisito de código:** plano `2026-10-26-01-spring-evolution-migration.md`, tarefas 1–6; ativação real requer evidência e autorização operacional, não uma POC separada.

## Restrições e revisão

- `ai_classification`, `intelligent_alerts` e `auto_reply` desativados por padrão; kill switch bloqueia ações reais sem desligar o fluxo legado.
- Shadow Mode abrange chats arquivados conhecidos, registra permissões e bloqueios, não envia respostas WhatsApp nem alerta individual simulado; relatórios administrativos seguem plano 3.
- Estado desconhecido, lacuna ou erro bloqueia ações; simulação e ações reais têm cooldowns separados; limite inicial real 60 minutos configurável.
- Trechos e payloads sensíveis expiram em até 7 dias; metadados por padrão em 90 dias configuráveis. Sem dados pessoais em logs.
- Revisão: override parcial não apaga herança; DST/virada de ano e dias municipais inválidos não inventam feriados; duas pessoas em um grupo não compartilham lote; repetição não viola cooldown; saída de IA não concede autorização.

## Arquivos e contratos

- `src/main/java/dev/hugo/watcher/policy/PolicyResolver.java`, `AvailabilityService.java`, `HolidayService.java`, `DecisionEngine.java`.
- `src/main/java/dev/hugo/watcher/simulation/DebounceService.java`, `SimulationService.java`.
- `src/main/resources/db/migration/V2__policy_simulation.sql`: perfis, overrides, templates versionados, feriados, flags, lotes, decisões e cooldowns.
- Testes correspondentes em `src/test/java/dev/hugo/watcher/policy/` e `.../simulation/`.

### Tarefa 1: Perfis, overrides e disponibilidade

**Interfaces:** `PolicyResolver.resolve(GlobalPolicy, Profile, ChatOverride): EffectivePolicy` retorna valor e origem de cada campo; `AvailabilityService.at(Instant, ZoneId, EffectivePolicy): Availability` prioriza override temporário > feriado validado > janela.

- [ ] Escrever `PolicyResolverTest` para herança parcial, override explícito e restauração; `AvailabilityServiceTest` para expiração, pontos facultativos opt-in, feriado SP/Lins validado, virada de ano e ausência de fonte.
- [ ] Rodar `mvn -Dtest=PolicyResolverTest,AvailabilityServiceTest test` e observar falha esperada.
- [ ] Criar migração V2 e implementar resolução por campo, WORK_ONLY/ANYTIME migráveis, regras temporárias e calendário com fonte/ano/status; não gerar feriado municipal presumido.
- [ ] Repetir testes e confirmar sucesso; commit somente arquivos da tarefa.

### Tarefa 2: Debounce e simulação

**Interfaces:** `DebounceService.enqueue(IncomingMessage, Instant)` agrupa por chat e remetente em grupos; `claimReady(Instant)` entrega lote após 10 s de inatividade ou 30 s desde primeira mensagem. `SimulationService.evaluate(Batch): SimulationDecision` persiste resultado sem efeitos externos.

- [ ] Escrever `DebounceServiceTest` com relógio injetável para 10 s, máximo 30 s, remetentes distintos, mensagens duplicadas e recuperação após reinício; `SimulationServiceTest` para archived UNKNOWN, bloqueios e falha de persistência.
- [ ] Rodar `mvn -Dtest=DebounceServiceTest,SimulationServiceTest test` e observar falha esperada.
- [ ] Implementar lotes duráveis com limites configuráveis, snapshots e auditoria `wouldAlert`, `wouldReply`, `templateId`, `decisionReason`, `blockedReason`.
- [ ] Repetir testes e confirmar sucesso; commit somente arquivos da tarefa.

### Tarefa 3: Autorizações, templates e kill switch

**Interfaces:** `DecisionEngine.decide(Batch, Classification, EffectivePolicy, Instant): Decision`; `Decision` contém motivo, estado e referências de template, sem enviar mensagens.

- [ ] Escrever `DecisionEngineTest`: saudação isolada não autoriza resposta; contato/grupo exige autorização e template; gatilho de grupo isolado não comprova referência pessoal; urgência crítica exige referência explícita; cooldown separado e kill switch bloqueiam ação real.
- [ ] Rodar `mvn -Dtest=DecisionEngineTest test` e observar falha esperada.
- [ ] Implementar precedência, regras fail-closed, templates versionados que identificam resposta automática, cooldown e flags; classificação é dado não confiável e não altera permissões.
- [ ] Repetir testes e confirmar sucesso; commit somente arquivos da tarefa.

**Saída:** regras e simulações verificadas localmente, sem ativação real. Os provedores de IA, menus e entregas são tratados no plano 3.

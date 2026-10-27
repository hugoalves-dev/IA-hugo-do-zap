# Plano 3 — IA, Telegram e ações controladas

> **Futuro, não autorizado para iniciar agora.** Só iniciar após concluir o plano 1 e receber aprovação explícita do usuário. A instrução vigente exige push por tarefa; esta atualização documental não inicia execução deste plano.

> **Para execução:** use `superpowers:executing-plans` ou `superpowers:subagent-driven-development`, com TDD e testes direcionados por tarefa.

**Objetivo:** adicionar classificação assistida, orçamento, administração Telegram e entrega controlada direta via Telegram Bot API/wrapper pengrad, com autorizações independentes da IA. n8n permanece apenas como transporte histórico durante coexistência/rollback.

**Arquitetura:** processadores do plano 2 chamam provedores por interface com limites transacionais; comandos administrativos privados modificam configurações auditadas; outbox entrega relatórios diretamente via Telegram Bot API usando `pengrad/java-telegram-bot-api` e, apenas após opt-in explícito, comandos WhatsApp via Evolution. Entrega Telegram é at-least-once: retry pode duplicar envio após resultado remoto incerto.

**Stack:** Java 25, Spring Boot 4.x, PostgreSQL/Flyway, cliente HTTP, Gemini e OpenRouter, Telegram Bot API e wrapper open-source `pengrad/java-telegram-bot-api`.

**Spec:** `docs/superpower/spec/2026-10-16-archived-watcher-automation-design.md`, com a rejeição da POC separada. **Pré-requisitos de código:** planos `2026-10-26-01-spring-evolution-migration.md` e `2026-10-26-02-policy-shadow-mode.md`.

## Restrições e revisão

- Sem uso pago sem orçamento diário e mensal configurados; sem recarga automática; reserva conservadora transacional evita concorrência acima do limite.
- Gemini primário; OpenRouter secundário somente para falha técnica, timeout, limite ou indisponibilidade; exigir `provider.zdr=true` e `provider.data_collection=deny`, e bloquear se a rota não cumprir.
- IA classifica entrada não confiável; nunca decide permissões, ferramentas, templates, orçamento ou ações. Falha/baixa confiança retorna `UNCERTAIN`.
- Telegram administra apenas usuário e chat privados configurados; polling é único consumidor e não pode coexistir com webhook de entrada do mesmo bot.
- Novos envios são desativados por padrão; kill switch prioritário, cooldown por conversa e template preaprovado são obrigatórios. Nunca chamar API de alterar arquivamento.
- Retenção máxima de trechos 7 dias; backups, Evolution, Telegram e provedores têm retenção separada. n8n só se aplica ao transporte legado durante coexistência/rollback. Evitar segredos, conteúdo bruto e identificadores em logs.
- Revisão: prompt injection não muda regras; falha de rota privada não vaza dados; concorrência não ultrapassa orçamento; callback antigo ou repetido não altera configuração; timeout de envio não é tratado como prova de não entrega.

## Arquivos e contratos

- `src/main/java/dev/hugo/watcher/ai/ClassificationProvider.java`, `GeminiProvider.java`, `OpenRouterProvider.java`, `ClassificationService.java`, `BudgetService.java`.
- `src/main/java/dev/hugo/watcher/telegram/TelegramUpdateHandler.java`, `CallbackTokenService.java`, `AdminCommandService.java`.
- `src/main/java/dev/hugo/watcher/notification/ReportService.java`, `WhatsAppActionService.java`.
- `src/main/resources/db/migration/V3__ai_admin_actions.sql`: reservas, custos, callbacks, auditoria, autorizações e operações de saída.
- Testes correspondentes em `src/test/java/dev/hugo/watcher/ai/`, `.../telegram/`, `.../notification/`.

### Tarefa 1: Classificação e privacidade

**Interfaces:** `ClassificationProvider.classify(ClassificationInput): ClassificationResult`; `ClassificationService.classify(Batch): ClassificationResult`; resultado inclui intenção, solicitação, urgência, evidências e confiança/estado.

- [ ] Criar `ClassificationServiceTest`, `GeminiProviderTest`, `OpenRouterProviderTest`: payload mínimo, validação de schema, injeção de instruções, erro/timeout/fallback, rota ZDR indisponível e resultado incerto.
- [ ] Rodar `mvn -Dtest=ClassificationServiceTest,GeminiProviderTest,OpenRouterProviderTest test` e observar falha esperada.
- [ ] Implementar clientes por HTTP fake testável, timeout/retries limitados, uma tentativa controlada em schema inválido, controles de privacidade e classificação sem acesso às permissões.
- [ ] Repetir testes e confirmar sucesso; commit arquivos da tarefa.

### Tarefa 2: Orçamento e retenção

**Interfaces:** `BudgetService.reserve(EstimatedCost, Instant): Reservation`; `settle(UUID, ActualCost)` e `release(UUID)` são idempotentes; ausência de limite retorna bloqueio antes de qualquer chamada paga.

- [ ] Criar `BudgetServiceTest` com PostgreSQL real: limites diário/mensal ausentes, concorrência, falha de chamada, reconciliação de custo e dupla liquidação; `AiRetentionTest` verifica purga de snippets e preservação de metadados conforme prazo.
- [ ] Rodar `mvn -Dtest=BudgetServiceTest,AiRetentionTest test` e observar falha esperada.
- [ ] Implementar migração V3, reservas transacionais, métricas sem payload e purga; documentação distingue custo estimado, informado e limites dos provedores.
- [ ] Repetir testes e confirmar sucesso; commit arquivos da tarefa.

### Tarefa 3: Administração Telegram privada

**Interfaces:** `TelegramUpdateHandler.handle(Update): AdminResult`; `CallbackTokenService.consume(String, long userId, long chatId): CallbackAction` aceita token único não expirado; `AdminCommandService.execute(AdminCommand): AdminResult` audita mutações.

- [ ] Criar `TelegramUpdateHandlerTest`, `CallbackTokenServiceTest`, `AdminCommandServiceTest` para usuário/chat incorretos, grupos, replay/expiração, confirmação de mudança perigosa, `/emergencia` sem IA e expiração de overrides.
- [ ] Rodar `mvn -Dtest=TelegramUpdateHandlerTest,CallbackTokenServiceTest,AdminCommandServiceTest test` e observar falha esperada.
- [ ] Implementar polling exclusivo via wrapper pengrad e comandos `/status`, `/ocupado`, `/disponivel`, `/ferias`, `/simulacao`, `/relatorio`, `/emergencia`, menus de políticas/templates/feriados/orçamentos, com auditoria sem segredos. Enviar respostas administrativas diretamente via Telegram Bot API.
- [ ] Repetir testes e confirmar sucesso; commit arquivos da tarefa.

### Tarefa 4: Relatórios e respostas autorizadas

**Interfaces:** `ReportService.schedule(LocalDate): OutboxAction` agrega decisões e custos; `WhatsAppActionService.enqueue(Decision): ActionResult` exige todas as permissões, template, cooldown, kill switch e chave idempotente antes de criar outbox.

- [ ] Criar `ReportServiceTest`, `WhatsAppActionServiceTest` para resumo diário sem conteúdo bruto, bloqueio de ação em Shadow Mode, opt-in por conversa/situação, limite/cooldown, autorização revogada antes do envio, timeout e tentativa duplicada.
- [ ] Rodar `mvn -Dtest=ReportServiceTest,WhatsAppActionServiceTest test` e observar falha esperada.
- [ ] Implementar notificações Telegram `simulation_daily_summary` e `intelligent_alert` via dispatcher outbox, cliente Evolution REST e reconciliação de resultado incerto; documentar Telegram at-least-once e não presumir exactly-once. Manter todo envio real de WhatsApp desligado.
- [ ] Repetir testes e confirmar sucesso; commit arquivos da tarefa.

### Tarefa 5: Aceitação e ativação

- [ ] Executar testes dirigidos de ponta a ponta com fakes Gemini/OpenRouter/Telegram/Evolution e PostgreSQL real; conferir budget, privacidade, kill switch e isolamento Shadow Mode.
- [ ] Preparar checklist de custos, rotas privadas, retenção, autorizações, rollback e evidências de paridade Evolution. Solicitar autorização operacional antes de configurar provedores pagos, parear dispositivo, alterar VPS ou ativar envios reais.
- [ ] Entregar resultados de testes, riscos e status de cada flag; não declarar integrações reais validadas apenas com fakes.

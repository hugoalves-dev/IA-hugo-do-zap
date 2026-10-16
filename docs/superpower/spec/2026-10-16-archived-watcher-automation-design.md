# WhatsApp Archived Watcher — Design de automações controladas e Shadow Mode

**Data:** 2026-10-16  
**Status:** Especificação para revisão do proprietário (não autoriza implementação)  
**Repositório:** `vitorhugo-dotnet/IA-hugo-do-zap`

## 1. Intenção, resultado e limites

Evoluir o watcher existente mantendo a integração Baileys em TypeScript e introduzindo um núcleo Spring Boot 4.x (Java 25 LTS, JVM convencional) para identificar solicitações e urgências em **conversas arquivadas** do WhatsApp, gerenciar disponibilidade por horário/feriados, notificar seletivamente pelo Telegram e, numa fase posterior e explicitamente autorizada, enviar respostas automáticas **apenas por templates preaprovados**. Administrar a solução por Telegram (comandos + botões), mantendo o n8n como transportador de mensagens; uma interface Next.js com SSR fica para o futuro.

**Critérios de sucesso:**
- Preservar integralmente o comportamento legado até a ativação consciente de cada recurso novo.
- Permitir simular regras em todas as conversas arquivadas sem enviar alertas individuais simulados nem respostas no WhatsApp.
- Demonstrar, na auditoria, por que uma ação seria permitida, bloqueada ou incerta.
- Nunca conceder permissões a partir da saída de IA; o modelo apenas classifica.
- Reduzir envio de dados pessoais a terceiros, impor teto de custos e não responder em situação de erro.

**Fora do escopo inicial:** painel Next.js, integração com Google Calendar, conversas não arquivadas, memória conversacional ampla, geração livre de texto, análise de mídia, substituição do Baileys e qualquer compilação Native Image/GraalVM. A mudança documentada não autoriza implementação ou deploy.

## 2. Base real observada no projeto

O repositório existente usa TypeScript/Node, Baileys `7.0.0-rc14`, `better-sqlite3`, `node-cron`, Docker e testes com `node:test`. Os módulos atuais principais são `src/whatsapp.ts`, `src/notify-handler.ts`, `src/watcher-state.ts`, `src/notifier.ts`, `src/config.ts` e `src/scheduler.ts`.

- `chat_state` armazena arquivamento, grupo, nome e política `WORK_ONLY`/`ANYTIME`, derivada de nome/regex; `contact_identity` resolve LID/PN/JID.
- `pending_notification` guarda contagens e **`last_message_preview`**; `processed_message` guarda hashes para deduplicação.
- O código coleta somente mensagens `notify` recebidas, elegíveis e com estado arquivado conhecido; ignora mensagens do próprio usuário, status e eventos de protocolo.
- O fluxo legado aplica janelas de expediente para `WORK_ONLY`, agrega pendências e envia um webhook autenticado ao n8n; o n8n entrega no Telegram.
- `watcher.db` já utiliza WAL. Os testes cobrem o fluxo legado, identidade, grupos, deduplicação, arquivamento e filtragem.
- O README está parcialmente desatualizado quanto a contato único/armazenamento: **não** usar sua descrição como fonte de verdade sobre a versão atual.

Na migração, preservar o watcher TypeScript e suas tabelas sem sobrescrita, até que o núcleo Java seja testado e ativado explicitamente.

## 3. Alternativas e arquitetura escolhida

**Escolha: Spring Boot 4.x, Java 25 LTS, JVM convencional, com Gateway Baileys TypeScript.** A motivação inclui experiência de portfólio Java. Não compilar Spring Boot com GraalVM/Native Image, não habilitar Spring Native/AOT e não impor treinamento de cache AOT. Maven + JAR JVM + Docker multi-stage. Avaliar CPU e memória antes de otimizações. Spring Boot 4 é compatível com Java 25: https://docs.spring.io/spring-boot/system-requirements.html.

São **dois processos com fronteira bem definida**, não uma plataforma de microservices:

```text
WhatsApp
   |
   v
[Baileys Gateway, Node.js/TypeScript: única sessão]
   |-- eventos chats.set/chats.update (preservam archived)
   |-- identidade canônica e aliases LID/PN/JID
   |-- eventos notify e saída futura de templates autorizados
   |-- outbox/retry duráveis e fluxo legado preservado
   |
   | HTTP interno autenticado + eventId/idempotência
   v
[Spring Boot / Java 25 / JVM convencional]
   |-- inbox de eventos e snapshot de chat
   |-- regras, debounce, feriados, disponibilidade e perfis
   |-- Shadow Mode, Gemini, OpenRouter e custos
   |-- auditoria, SQLite próprio, Telegram administrativo
   |
   v
[n8n existente] -> [Telegram]

Enquanto não houver cutover:
[Fluxo legado TypeScript] -> [n8n existente] -> [Telegram]
```

**Gateway**: continuar usando Baileys diretamente. Entregar eventos de arquivamento/desarquivamento, snapshots iniciais e mensagens novas. Não criar uma segunda sessão simultânea para a mesma conta. `archived` deve ser um estado explícito, não inferido a partir de ausência de campo. Manter resolução LID/PN/JID confiável.

**Contrato**: evento versionado com `eventId` estável, tipo, horário, identidade canônica e aliases, estado `archived` quando conhecido, ordenação/versão de estado e apenas o conteúdo mínimo permitido. HTTP interno em Docker Compose, HMAC sobre corpo/timestamp, proteção contra replay, filas/outbox limitadas e retry com backoff. No Spring, persistir inbox antes do ACK, deduplicar e processar assincronamente. Sem brokers, Kubernetes ou redistribuição de responsabilidades prematura.

**Ownership**: TypeScript é dono da sessão Baileys, reconexões e coleta; Java é dono das políticas, IA, simulação, comandos administrativos e decisões futuras. O legado continua emitindo notificações reais até o cutover; novas regras em Shadow Mode não enviam mensagens WhatsApp nem avisos individuais simulados. Relatório diário e respostas a comandos administrativos são exceções intencionais.

**Banco**: `watcher.db` permanece no TypeScript; `automation.db` é de propriedade exclusiva do Spring. Não permitir escrita compartilhada entre runtimes. Sincronizar estado e migrar políticas por snapshot/exportação versionada.

**Evolution API/forks:** opcionais apenas para POC. Adoção exige verificar preservação de `archived` nos webhooks `chats.update`, sincronização inicial, reconexão e mapeamentos LID/PN; o projeto não dependerá de correção upstream. A versão oficial 2.3.7 consultada descartava esse campo. Caso a POC não cumpra o contrato, seguir com Gateway próprio.

**Comparativo de frameworks**: ASP.NET Core Minimal API e Quarkus funcionariam como consumidores HTTP, mas Spring Boot reforça o portfólio Java desejado sem exigir mudança do gateway. Quarkus nativo também pode envolver GraalVM/Mandrel; não é vantagem perseguida. Spring AOT e Java 25 AOT Cache são coisas distintas de Native Image; **nenhuma delas é requisito deste projeto**.

**Migração/rollback**: implementar o core Java isoladamente, executar Shadow Mode e validar paridade, duplicidade e recuperação de falhas antes de direcionar saída real ao Java. O legado não pode ser interrompido por falha no Java. Comutação de saída deve impedir alertas duplicados e ser reversível.

## 4. Escopo e filtragem de mensagens

- Monitorar **apenas conversas com `archived=true`** no instante da recepção. Desarquivar torna a conversa inelegível para novas decisões; arquivar novamente torna-a elegível.
- Abranger contatos privados e grupos arquivados. O Shadow Mode é global para todos eles, sem importar regras que bloqueiam ações; registrar ações que *teriam* sido negadas.
- Processar **mensagens novas**, não varrer/reprocessar histórico indiscriminadamente. O Gateway informa o estado de arquivamento conhecido no instante do evento e entrega snapshots e alterações em ordem; ausência, defasagem ou conflito de estado causa descarte seguro com motivo auditável.
- Identidade estável: o Gateway resolve LID/PN/JID e encaminha identificador canônico e aliases ao Java para preservar overrides mesmo quando o nome muda.
- Deduplicar no Java por identidade de mensagem e `eventId` em índice próprio. Não alterar a deduplicação do legado. Eventos atrasados não podem sobrescrever estados mais novos.
- Debounce **10 segundos de inatividade**, com **30 segundos máximos** por lote. Um buffer por conversa; em grupos segmentar lotes por remetente para não misturar solicitações. Limites de tamanho de lote e concorrência são configuráveis.
- Filtragem barata antes da IA: arquivamento, tipo de mensagem, permissões, bloqueios, referências explícitas e palavras-chave. O fato de o Shadow Mode observar todas as conversas não obriga a realizar chamada à IA em todas.
- Sem baixar mídia. Texto/captions suportados podem ser classificados mediante política de privacidade; conteúdo inacessível gera `UNSUPPORTED_CONTENT`, sem resposta automática.
- Mensagens de terceiros são entrada **não confiável**, inclusive quando direcionadas ao classificador; instruções inseridas nelas não podem alterar políticas, templates, ferramentas nem orçamento.

## 5. Regras, disponibilidade e templates

**Precedência:** configuração global → perfil compartilhado → override por conversa. Os perfis iniciais `WORK_ONLY` e `ANYTIME` devem ser preservados e migrados sem alterar o despacho legado. Adicionar suporte a outros perfis no futuro sem hardcode de novos enums na regra de negócio.

Overrides são parciais e validados: campo não definido herda do perfil; valor explicitamente definido prevalece. A UI Telegram precisa mostrar o valor efetivo e sua origem, permitindo restaurar herança.

**Disponibilidade:** override temporário via Telegram (`/ocupado 2h`, `/disponivel 1h`, `/ferias`) > feriado/regras específicas > janelas de expediente. Overrides temporários expiram automaticamente. Não integrar eventos de calendário nesta fase.

**Calendário de feriados:** Brasil (nacionais), SP (estaduais) e Lins (municipais). Manter data, âmbito, fonte, ano e status de validade. Preferir publicações oficiais; atualizar/corrigir anualmente, validar manualmente datas municipais antes de publicar o calendário. Pontos facultativos não mudam disponibilidade automaticamente: podem ser habilitados por data ou regra. Sem fontes verificadas, nunca inventar feriado; registrar status desconhecido e usar fallback seguro/documentado.

**Conversas individuais:** uma resposta automática só seria elegível se a IA detectar solicitação real, a situação e o contato tiverem autorização explícita e existir template correspondente. Um simples cumprimento isolado não autoriza resposta.

**Grupos:** permitir respostas automatizadas apenas se o grupo estiver autorizado e uma **menção** ou **palavra-chave** configurada ativar a regra. Qualquer participante pode acionar o gatilho. Autorizações continuam sendo por grupo/situação/template, não pela IA. Cooldown, limite de frequência e deduplicação evitam loops/spam. Templates devem declarar que são resposta automática e não se passar pelo usuário.

**Alertas críticos durante indisponibilidade:** exigir **urgência crítica E referência pessoal explícita**, reconhecida por menção estrutural, nome/apelido cadastrado ou resposta a mensagem própria. Não inferir vínculo com base em projeto/atividade que pareça relacionada. Palavra-chave de grupo, isoladamente, não constitui referência pessoal para esse alerta. Para detectar resposta à própria mensagem, registrar apenas identificadores mínimos das mensagens de saída, quando disponíveis; se não houver evidência verificável, o alerta crítico é bloqueado.

Cada recurso novo (`ai_classification`, `intelligent_alerts`, `auto_reply`) nasce desativado em produção; simulação habilitável independentemente. **Kill switch** desativa todas as novas ações reais, sem desativar o legado. Cooldowns reais e simulados são isolados. Valor inicial sugerido para respostas reais: 60 minutos por conversa, editável na configuração antes de ativar.

## 6. Pipeline e tratamento de erros

1. O Gateway Baileys recebe estado de chats, identidades e mensagens novas; persiste o evento na outbox limitada, entrega ao Spring por HTTP interno autenticado e mantém o pipeline legado independente.
2. O Spring valida HMAC, timestamp, versão, tamanho e schema do evento, persiste na inbox SQLite e só então envia ACK. Reentrega do mesmo `eventId` não reprocessa. Eventos fora de ordem não regridem o estado de `archived`.
3. Validar elegibilidade de conversa arquivada e bufferizar mensagens por **10s de inatividade**, com máximo de **30s**, separando remetentes dentro de grupos.
4. Resolver `global → profile → override`, contexto de disponibilidade e gatilhos. Chamar `ClassificationService` apenas quando filtros determinísticos permitirem.
5. Validar resultado da IA, autorizações, orçamento e cooldown **fora** do classificador.
6. Persistir `Decision` (`wouldAlert`, `wouldReply`, `templateId`, `decisionReason`, `blockedReason`) e auditoria; calcular relatório diário. Em Shadow Mode não há efeito colateral WhatsApp.
7. Quando explicitamente habilitado no futuro, qualquer envio WhatsApp passa por endpoint interno restrito no Gateway, com template autorizado, kill switch e chave de idempotência. Telegram continua roteado via n8n.

**Fail-closed:** falha HTTP/assinatura, replay, timeout, JSON inválido, provedor indisponível, política de dados incompatível, confiança insuficiente, custo acima do teto, arquivamento desconhecido e erro de persistência impedem ações reais. Resultado incerto gera `UNCERTAIN` sem fallback por discordância subjetiva.

Fila de recepção, processamento e erros ficam limitados e auditáveis, com retry/backoff e recuperação após reinício. Se o Java cair, o Gateway preserva os eventos elegíveis sem impedir notificações legadas. Entregas e decisões devem ser idempotentes mesmo após crash. Não introduzir broker externo sem necessidade demonstrada.

## 7. Classificação de IA, fallback, custos e privacidade

`ClassificationProvider` padroniza a entrada e retorna saída estruturada (intenção, solicitação, urgência, evidências, confiança/estado), permitindo trocar modelos sem alterar regras.

- **Primário:** Gemini API, **tier pago** (identificador exato do modelo configurável).
- **Secundário:** OpenRouter com `anthropic/claude-haiku-5.5`.
- Fallback apenas para erro de transporte, timeout, limite de uso ou indisponibilidade, com número pequeno de tentativas; falha de esquema pode gerar no máximo uma tentativa controlada sem loops.
- Em OpenRouter exigir `provider.zdr=true` e `provider.data_collection="deny"`. Se nenhum endpoint compatível aceitar essas condições, **não enviar** a solicitação. Conferir periodicamente elegibilidade efetiva do modelo e rota.
- Habilitar proteção de privacidade também nas configurações da conta/chave do provedor, além dos parâmetros da requisição.
- Antes de ativar uso pago, exigir orçamento diário e mensal **explicitamente configurados**; sem configuração, bloquear classificação paga. Aplicar reserva conservadora/contagem de chamadas no Spring e teto na chave do OpenRouter quando possível. Não habilitar recarga automática; alertar sobre aproximação/atingimento do limite. Monitorar consumo do Gemini à parte porque o limite local não substitui controles de faturamento da nuvem.
- Logar provedor/modelo, tokenização informada pela API, estimativa/custo retornado, latência, resultado e motivos de fallback **sem** payload completo.
- Enviar ao modelo somente o mínimo necessário, removendo telefones, identificadores internos e metadados supérfluos. Ter política para dados altamente sensíveis que não devem ser transmitidos.
- Diferenciar explicitamente retenção do SQLite local de retenção no n8n, no Telegram e nos serviços de IA. O limite local **não apaga** dados já transmitidos a terceiros.

Referências oficiais verificadas em 2026-10-16:
- Gemini API pricing/data usage: https://ai.google.dev/gemini-api/docs/pricing
- OpenRouter route privacy controls: https://openrouter.ai/docs/guides/get-started/sovereign-ai
- OpenRouter guardrails: https://openrouter.ai/docs/guides/features/guardrails/overview
- Modelo secundário: https://openrouter.ai/anthropic/claude-haiku-5.5

## 8. Persistência, migração e retenção

O TypeScript **continua dono de `watcher.db`**, com WAL e schema legado intactos. O Java usa **SQLite separado `automation.db`**, com migrações idempotentes/versionadas, WAL e transações curtas. Preferir JDBC/JdbcClient com SQL explícito e driver SQLite em vez de presumir suporte completo de ORM/dialeto; sem PostgreSQL ou Redis obrigatórios.

| Domínio Java | Responsabilidade |
| --- | --- |
| `inbox_events` | Idempotência, ordenação, falhas e recuperação após reinício |
| `chat_snapshot` e `identity_aliases` | Estado archived conhecido e identidade canônica LID/PN/JID |
| `profiles` | Configurações compartilhadas, versão e habilitações |
| `chat_policies` | Perfil e overrides por identidade |
| `response_templates` | Texto versionado, situação e escopo autorizado |
| `holiday_rules` | Feriados, pontos facultativos, origem e validade |
| `simulation_decisions` | Decisão, motivos, custos e regras/modelos usados |
| `system_settings` | Flags, kill switch, retenção e limites não secretos |

**Migração:** exportar/sincronizar estado e políticas existentes por canal controlado, preservando `chat_state.notification_policy` e `contact_identity` no banco TypeScript. Não realizar escrita cruzada no mesmo SQLite. Não migrar regras por nome exibido sem chave canônica. Reconciliar divergências e demonstrar rollback antes do cutover.

**Conteúdo de auditoria:** trecho limitado e sanitizado armazenado por no máximo **7 dias**, incluindo buffers/outbox do Gateway e `pending_notification.last_message_preview` existente, sem perder contagens do legado. Purga automática testada em limites de data e reinício. Metadados de decisão, sem texto: retenção padrão **90 dias**, configurável.

Nunca persistir tokens, chaves ou credenciais em SQLite. Proteger volumes, logs e backups.

## 9. Telegram e n8n

**MVP Telegram:** comandos rápidos (`/status`, `/ocupado`, `/disponivel`, `/ferias`, `/simulacao`, `/relatorio`, `/emergencia`) e menus com botões para perfis, conversas, grupos, templates, pontos facultativos, orçamento, modo simulado e autorizações. Respostas administrativas e resumos usam o n8n para envio; o Watcher não deve duplicar o cliente de mensagens Telegram sem necessidade.

O Spring Boot recebe `getUpdates` por long polling como **único consumidor de updates** da conta do bot. Se houver webhook de entrada nesse bot, não ativar polling simultaneamente. Caso o menu exija reconhecer callbacks por API, manter esse trecho limitado e documentado, sem mover o sistema de dispatch do n8n para o código Java.

**Segurança:** administrador único por `TELEGRAM_ADMIN_USER_ID`, `TELEGRAM_ADMIN_CHAT_ID` e tipo de chat privado; validar autor, chat e callback em toda operação. Confirmar operações destrutivas, mudança de regras globais e ativação de envios reais. Botões antigos, expirados e já consumidos são inválidos. Auditoria de comandos sem segredos. `/emergencia` tem prioridade e funciona sem IA.

**Contrato com n8n:** manter `WhatsApp Archived Watcher - Telegram Digest` ativo e seu webhook autenticado. Acrescentar somente suporte retrocompatível a novos tipos de payload (`simulation_daily_summary`, `intelligent_alert` e interações de UI necessárias), mantendo o papel de transporte. A formatação e a escolha da ação pertencem ao Spring, mas o envio Telegram permanece no workflow; verificar quais ajustes pontuais no node `Montar mensagem` serão necessários antes da implementação. Não quebrar `archived_messages` nem alertas de reconexão.

**Relatório do Shadow Mode:** resumo diário em horário configurável (mensagens candidatas, chamadas IA, solicitações, críticos, bloqueios, custos, erros e simulações de resposta); `/relatorio` com filtros e detalhes. Não enviar automaticamente cada evento simulado. O resumo diário é mensagem administrativa real, explicitamente autorizada, sem conteúdo de mensagem bruta.

**Next.js SSR (futuro):** adicionar API autenticada sobre serviços Spring já existentes, sem API pública/painel nesta fase.

## 10. Estratégia de testes e aceitação

Cobertura automatizada de unidade, contrato HTTP e integração (fakes para Baileys, Gemini, OpenRouter, n8n e Telegram; SQLite real em testes), com casos:
- Legado continua notificando com política/janelas pré-existentes, mesmo quando Shadow Mode ou IA falham.
- Todas as conversas arquivadas podem ser simuladas; conversas normais e histórico remoto não são processados.
- Desarquivar, read-status, exclusões, aliases LID/PN, eventos duplicados, grupos e mensagens do próprio usuário.
- Debounce de 10s/30s e divisão por remetente nos grupos, com relógio injetável.
- Palavra-chave de grupo pode selecionar **template**, mas **não** gerar alerta crítico sem referência pessoal explícita.
- Solicitação em contato individual requer IA + permissão + template + cooldown. `oi` isolado não autoriza.
- Feriados nacionais/SP/Lins, pontos facultativos opt-in, fuso `America/Sao_Paulo`, virada do ano e override expirado.
- Inheritance/override por campo, restauração de herança e migrações idempotentes.
- Resultados `UNCERTAIN`, prompt injection, erros HTTP, falta de rota ZDR, indisponibilidade, limites de custo e saídas de esquema inválido fecham a autorização.
- Simulação não toca envio WhatsApp nem gera alerta inteligente real; cooldown simulado não toca cooldown real.
- Expiração de snippets em até 7 dias inclusive preview legado, metadados mantidos segundo política.
- Telegram rejeita administrador incorreto, grupos, replay de callbacks e mudanças perigosas sem confirmação.
- Payload legado no n8n permanece aceito e entrega os mesmos avisos.
- Eventos `archived` iniciais, updates, desarquivamento, reconexão, mensagens em ordem inversa e identidades LID/PN.
- HMAC, replay, schema inválido, reentrega duplicada, ACK somente após persistência, queda do Java, retries e recuperação de inbox/outbox.
- Fluxo legado mantém notificações durante todo Shadow Mode, sem duplicar envio no cutover e com rollback testado.
- Spring Boot 4.x/Java 25 executa em JAR JVM/container sem GraalVM, Native Image ou pipeline AOT; medir consumo na VPS.

**Critério de liberação:** Shadow Mode primeiro; revisar amostras de falsos positivos/negativos e custos reais; liberar cada tipo de ação por opt-in de perfil/conversa/situação e com kill switch testado. O uso de Baileys e envios automatizados numa conta pessoal envolve risco de restrição pelo WhatsApp, aceito como risco operacional explícito, não eliminado pelas permissões locais.

## 11. Incrementos sugeridos (ainda não são plano de implementação)

1. Definir contrato de eventos/versionamento/HMAC e outbox no TypeScript, garantindo `archived` e LID/PN; desenhar isolamento e rollback sem tocar no serviço em produção.
2. Criar core Spring Boot 4.x Java 25 **JVM convencional**, Docker multi-stage, SQLite próprio e inbox idempotente. Sem Native Image, GraalVM, Spring AOT ou banco compartilhado.
3. Implementar Shadow Mode em Java com migração/snapshot controlado, debounce, perfis, políticas, auditoria e todos os testes de segurança. Manter notificação real do TypeScript.
4. Implementar Gemini/OpenRouter, orçamento e retenção; validar modelos com custos limitados e fallback fail-closed.
5. Mover administração Telegram ao Spring e relatório diário para n8n, preservando o workflow legado.
6. Comparar paridade e ativar seletivamente ações reais após opt-in e kill switch, com cutover reversível e sem notificações duplicadas.
7. Painel Next.js SSR somente se necessário; Evolution API/forks como POC opcional não bloqueante.

**Não implementar ou implantar esta arquitetura até uma aprovação explícita do plano de execução.**

## 12. Aprovação e próximo gate

Este documento registra as decisões do brainstorming e a revisão para **Spring Boot 4.x rodando na JVM, sem GraalVM/AOT nativo**. O proprietário deve revisar e aprovar **esta especificação escrita**, inclusive os defaults propostos (cooldown inicial de 60 minutos, retenção de metadados de 90 dias, limites de fila e necessidade de configurar orçamento). Só então iniciar `superpowers:writing-plans` para produzir o plano executável. **Nenhum código de produção é autorizado por esta spec isoladamente.**

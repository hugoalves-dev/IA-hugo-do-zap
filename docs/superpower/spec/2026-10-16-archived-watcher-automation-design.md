# WhatsApp Archived Watcher — Design de automações controladas e Shadow Mode

**Data:** 2026-10-16  
**Status:** Especificação para revisão do proprietário (não autoriza implementação)  
**Repositório:** `vitorhugo-dotnet/IA-hugo-do-zap`

## 1. Intenção, resultado e limites

Evoluir o serviço pessoal existente em TypeScript para identificar solicitações e urgências em **conversas arquivadas** do WhatsApp, gerenciar disponibilidade por horário/feriados, notificar seletivamente pelo Telegram e, numa fase posterior e explicitamente autorizada, enviar respostas automáticas **apenas por templates preaprovados**. Administrar a solução por Telegram (comandos + botões), mantendo o n8n como transportador de mensagens; uma interface Next.js com SSR fica para o futuro.

**Critérios de sucesso:**
- Preservar integralmente o comportamento legado até a ativação consciente de cada recurso novo.
- Permitir simular regras em todas as conversas arquivadas sem enviar alertas individuais simulados nem respostas no WhatsApp.
- Demonstrar, na auditoria, por que uma ação seria permitida, bloqueada ou incerta.
- Nunca conceder permissões a partir da saída de IA; o modelo apenas classifica.
- Reduzir envio de dados pessoais a terceiros, impor teto de custos e não responder em situação de erro.

**Fora do escopo inicial:** painel Next.js, integração com eventos do Google Calendar, conversas não arquivadas, memória conversacional ampla, geração livre de texto pelo modelo, download/análise de mídia e troca da implementação WhatsApp atual.

## 2. Base real observada no projeto

O repositório existente usa TypeScript/Node, Baileys `7.0.0-rc14`, `better-sqlite3`, `node-cron`, Docker e testes com `node:test`. Os módulos atuais principais são `src/whatsapp.ts`, `src/notify-handler.ts`, `src/watcher-state.ts`, `src/notifier.ts`, `src/config.ts` e `src/scheduler.ts`.

- `chat_state` armazena arquivamento, grupo, nome e política `WORK_ONLY`/`ANYTIME`, derivada de nome/regex; `contact_identity` resolve LID/PN/JID.
- `pending_notification` guarda contagens e **`last_message_preview`**; `processed_message` guarda hashes para deduplicação.
- O código coleta somente mensagens `notify` recebidas, elegíveis e com estado arquivado conhecido; ignora mensagens do próprio usuário, status e eventos de protocolo.
- O fluxo legado aplica janelas de expediente para `WORK_ONLY`, agrega pendências e envia um webhook autenticado ao n8n; o n8n entrega no Telegram.
- `watcher.db` já utiliza WAL. Os testes cobrem o fluxo legado, identidade, grupos, deduplicação, arquivamento e filtragem.
- O README está parcialmente desatualizado quanto a contato único/armazenamento: **não** usar sua descrição como fonte de verdade sobre a versão atual.

O novo design não sobrescreve de imediato o comportamento das tabelas e dos módulos legados.

## 3. Alternativas e arquitetura escolhida

**Escolhida: pipeline paralelo no mesmo processo Watcher.** Reaproveita o serviço, a base SQLite e o deploy existentes. Dois fluxos recebem o mesmo evento de entrada, mas possuem filas, estados, erros e decisões independentes.

Alternativas rejeitadas: segundo worker/processo (isolamento operacional melhor, manutenção maior) e motor de regras no n8n (acoplamento e testes de política mais difíceis). O n8n permanece apenas na entrega/roteamento operacional do Telegram, sem se tornar autoridade de regras ou IA.

```text
WhatsApp / Baileys — mensagens novas, apenas chats arquivados
          |
          +--> Fluxo legado --> SQLite legado --> scheduler --> n8n --> Telegram
          |
          +--> Pipeline de simulação/novas automações
                 -> deduplicação própria -> debounce
                 -> filtros determinísticos + perfis + disponibilidade
                 -> IA (quando necessária) -> validação
                 -> motor de decisão / dry run -> auditoria SQLite
                 -> relatório diário via n8n / consultas Telegram

Telegram private chat -> getUpdates (Watcher) -> autorização de administrador
                      -> comandos / menus -> configurações SQLite
                      -> respostas e relatórios enviados via n8n
```

Toda falha do novo pipeline deve ser capturada sem impedir a coleta, fila e disparos do legado. O Shadow Mode não chama nenhuma função capaz de enviar mensagens ao WhatsApp ou alertas individuais simulados; o envio do **relatório diário** e as respostas a comandos administrativos são exceções intencionais e separadas da simulação.

## 4. Escopo e filtragem de mensagens

- Monitorar **apenas conversas com `archived=true`** no instante da recepção. Desarquivar torna a conversa inelegível para novas decisões; arquivar novamente torna-a elegível.
- Abranger contatos privados e grupos arquivados. O Shadow Mode é global para todos eles, sem importar regras que bloqueiam ações; registrar ações que *teriam* sido negadas.
- Processar **mensagens novas**, não varrer/reprocessar histórico indiscriminadamente. A falta de estado de arquivamento conhecido causa descarte seguro com motivo auditável.
- Identidade estável: reutilizar resolução LID/PN/JID para preservar overrides mesmo quando o nome muda.
- Deduplicar por identidade de mensagem; no pipeline novo, usar namespace/índice próprio, sem alterar a deduplicação do legado.
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

1. Capturar evento elegível e deduplicar.
2. Acumular no buffer de debounce, separando remetentes dentro dos grupos.
3. Resolver `global → profile → override`, contexto de disponibilidade e gatilhos.
4. Se a mensagem requerer classificação, chamar `ClassificationService`, fornecendo somente texto mínimo e contexto necessário; sem históricos longos.
5. Validar esquema do retorno, permissões, autorização, cooldown e orçamento **fora** da IA.
6. Gerar `Decision` com `wouldAlert`, `wouldReply`, `templateId`, `decisionReason` e `blockedReason`, sem efeitos colaterais em Shadow Mode.
7. Persistir decisão, referências técnicas e trecho de auditoria; agregar métricas para resumo diário.

**Fail-closed:** timeout, JSON inválido, provedor indisponível, política de dados incompatível, confiança insuficiente, custo acima do teto, estado de arquivamento incerto ou erro de persistência impedem qualquer nova ação real. Classificação incerta gera `UNCERTAIN`; não há fallback de IA por discordância subjetiva com o resultado.

O novo pipeline terá limites próprios de fila, taxa e concorrência, backoff controlado e métricas de falhas. Eventos em processamento podem ser guardados de forma limitada no SQLite para recuperação após reinício, sob a mesma retenção de conteúdo; evitar guardar texto completo indefinidamente. Reavaliação/duplicação não pode disparar duas respostas reais.

## 7. Classificação de IA, fallback, custos e privacidade

`ClassificationProvider` padroniza a entrada e retorna saída estruturada (intenção, solicitação, urgência, evidências, confiança/estado), permitindo trocar modelos sem alterar regras.

- **Primário:** Gemini API, **tier pago** (identificador exato do modelo configurável).
- **Secundário:** OpenRouter com `anthropic/claude-haiku-5.5`.
- Fallback apenas para erro de transporte, timeout, limite de uso ou indisponibilidade, com número pequeno de tentativas; falha de esquema pode gerar no máximo uma tentativa controlada sem loops.
- Em OpenRouter exigir `provider.zdr=true` e `provider.data_collection="deny"`. Se nenhum endpoint compatível aceitar essas condições, **não enviar** a solicitação. Conferir periodicamente elegibilidade efetiva do modelo e rota.
- Habilitar proteção de privacidade também nas configurações da conta/chave do provedor, além dos parâmetros da requisição.
- Antes de ativar uso pago, exigir orçamento diário e mensal **explicitamente configurados**; sem configuração, bloquear classificação paga. Aplicar reserva conservadora/contagem de chamadas no Watcher e teto na chave do OpenRouter quando possível. Não habilitar recarga automática; alertar sobre aproximação/atingimento do limite. Monitorar consumo do Gemini à parte porque o limite local não substitui controles de faturamento da nuvem.
- Logar provedor/modelo, tokenização informada pela API, estimativa/custo retornado, latência, resultado e motivos de fallback **sem** payload completo.
- Enviar ao modelo somente o mínimo necessário, removendo telefones, identificadores internos e metadados supérfluos. Ter política para dados altamente sensíveis que não devem ser transmitidos.
- Diferenciar explicitamente retenção do SQLite local de retenção no n8n, no Telegram e nos serviços de IA. O limite local **não apaga** dados já transmitidos a terceiros.

Referências oficiais verificadas em 2026-10-16:
- Gemini API pricing/data usage: https://ai.google.dev/gemini-api/docs/pricing
- OpenRouter route privacy controls: https://openrouter.ai/docs/guides/get-started/sovereign-ai
- OpenRouter guardrails: https://openrouter.ai/docs/guides/features/guardrails/overview
- Modelo secundário: https://openrouter.ai/anthropic/claude-haiku-5.5

## 8. Persistência, migração e retenção

Usar **SQLite existente**, com migrações idempotentes, transações curtas e WAL já presente. Entidades lógicas (não obrigatoriamente seis novas tabelas):

| Domínio | Responsabilidade |
| --- | --- |
| `profiles` | Configurações compartilhadas, versão e habilitações |
| `chat_policies` | Identificador canônico, perfil atribuído, overrides tipados/validados |
| `response_templates` | Texto imutável/versionado, situação, escopo de autorização |
| `holiday_rules` | Feriados, pontos facultativos e fontes/validade |
| `simulation_decisions` | Decisão, motivo, referência, custo, regras/modelo usados |
| `system_settings` | Flags globais, kill switch, retenção e limites não secretos |

Manter o schema legado funcional durante migração, sem alterar `chat_state.notification_policy` até haver compatibilidade demonstrada. Resolver alterações de nomes/JIDs usando `contact_identity`; não copiar regras com base apenas no nome visível.

**Conteúdo de auditoria:** trecho limitado, sanitizado, guardado no máximo **7 dias**. Purga automática com testes para data limite e reinício; consultas não exibem conteúdo já vencido. A política deve cobrir também `pending_notification.last_message_preview` já existente, que hoje pode permanecer enquanto pendente, sem quebrar contagens de notificações. Contagens, status e estatísticas podem permanecer sem texto; adotar por padrão retenção de metadados por 90 dias para decisões e permitir alteração explícita.

Nunca persistir chaves de API ou tokens de bot no SQLite. Dados de identificação são técnicos e devem ter proteção de acesso no volume Docker e nos logs.

## 9. Telegram e n8n

**MVP Telegram:** comandos rápidos (`/status`, `/ocupado`, `/disponivel`, `/ferias`, `/simulacao`, `/relatorio`, `/emergencia`) e menus com botões para perfis, conversas, grupos, templates, pontos facultativos, orçamento, modo simulado e autorizações. Respostas administrativas e resumos usam o n8n para envio; o Watcher não deve duplicar o cliente de mensagens Telegram sem necessidade.

O Watcher recebe `getUpdates` por long polling como **único consumidor de updates** da conta do bot. Se houver webhook de entrada nesse bot, não ativar polling simultaneamente. Caso o menu exija reconhecer callbacks por API, manter esse trecho limitado e documentado, sem mover o sistema de dispatch do n8n para o código.

**Segurança:** administrador único por `TELEGRAM_ADMIN_USER_ID`, `TELEGRAM_ADMIN_CHAT_ID` e tipo de chat privado; validar autor, chat e callback em toda operação. Confirmar operações destrutivas, mudança de regras globais e ativação de envios reais. Botões antigos, expirados e já consumidos são inválidos. Auditoria de comandos sem segredos. `/emergencia` tem prioridade e funciona sem IA.

**Contrato com n8n:** manter `WhatsApp Archived Watcher - Telegram Digest` ativo e seu webhook autenticado. Acrescentar somente suporte retrocompatível a novos tipos de payload (`simulation_daily_summary`, `intelligent_alert` e interações de UI necessárias), mantendo o papel de transporte. A formatação e a escolha da ação pertencem ao Watcher, mas o envio Telegram permanece no workflow; verificar quais ajustes pontuais no node `Montar mensagem` serão necessários antes da implementação. Não quebrar `archived_messages` nem alertas de reconexão.

**Relatório do Shadow Mode:** resumo diário em horário configurável (mensagens candidatas, chamadas IA, solicitações, críticos, bloqueios, custos, erros e simulações de resposta); `/relatorio` com filtros e detalhes. Não enviar automaticamente cada evento simulado. O resumo diário é mensagem administrativa real, explicitamente autorizada, sem conteúdo de mensagem bruta.

**Next.js SSR (futuro):** expor futuramente uma API autenticada sobre os mesmos serviços de regra e persistência; não criar API pública nesta fase.

## 10. Estratégia de testes e aceitação

Cobertura automatizada isolada de integração de rede (fakes para Baileys, Gemini, OpenRouter, n8n e Telegram), com casos:
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

**Critério de liberação:** Shadow Mode primeiro; revisar amostras de falsos positivos/negativos e custos reais; liberar cada tipo de ação por opt-in de perfil/conversa/situação e com kill switch testado. O uso de Baileys e envios automatizados numa conta pessoal envolve risco de restrição pelo WhatsApp, aceito como risco operacional explícito, não eliminado pelas permissões locais.

## 11. Incrementos sugeridos (ainda não são plano de implementação)

1. Migrações de política/retention + estados/flags, sem mudança no envio atual.
2. Shadow Mode global e classificações mockadas, com testes de segurança e isolamento.
3. Gemini/OpenRouter e orçamento; filtros de privacidade.
4. Telegram administrativo híbrido e auditoria/relatório diário via n8n.
5. Ativação opt-in de alertas inteligentes; só depois templates automáticos e cooldowns reais.
6. Painel Next.js SSR apenas depois de avaliar demanda real.

## 12. Aprovação e próximo gate

Este documento registra as decisões do brainstorming. O proprietário deve revisar e aprovar **esta especificação escrita**, inclusive os defaults propostos (cooldown inicial de 60 minutos, retenção de metadados de 90 dias, limites de fila e necessidade de configurar orçamento). Só então iniciar `superpowers:writing-plans` para produzir o plano executável. **Nenhum código de produção é autorizado por esta spec isoladamente.**

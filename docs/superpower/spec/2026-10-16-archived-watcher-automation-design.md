# WhatsApp Archived Watcher — Design de automações controladas e Shadow Mode

**Data:** 2026-10-16  
**Status:** Especificação para revisão do proprietário (não autoriza implementação)  
**Repositório:** `vitorhugo-dotnet/IA-hugo-do-zap`

## 1. Intenção, resultado e limites

Evoluir o watcher existente substituindo gradualmente a integração direta com Baileys por **Evolution API**, testando primeiro a distribuição **oficial** e recorrendo ao **fork não oficial de AlmogCohen** caso a oficial não atenda aos critérios de aceitação, e introduzindo um núcleo Spring Boot 4.x (Java 25 LTS, JVM convencional) para identificar solicitações e urgências em **conversas arquivadas** do WhatsApp, gerenciar disponibilidade por horário/feriados, notificar seletivamente pelo Telegram e, numa fase posterior e explicitamente autorizada, enviar respostas automáticas **apenas por templates preaprovados**. Administrar a solução por Telegram (comandos + botões), mantendo o n8n como transportador de mensagens; uma interface Next.js com SSR fica para o futuro.

**Critérios de sucesso:**
- Preservar integralmente o comportamento legado até a ativação consciente de cada recurso novo.
- Permitir simular regras em todas as conversas arquivadas sem enviar alertas individuais simulados nem respostas no WhatsApp.
- Demonstrar, na auditoria, por que uma ação seria permitida, bloqueada ou incerta.
- Nunca conceder permissões a partir da saída de IA; o modelo apenas classifica.
- Reduzir envio de dados pessoais a terceiros, impor teto de custos e não responder em situação de erro.

**Fora do escopo inicial:** painel Next.js, integração com Google Calendar, conversas não arquivadas, memória conversacional ampla, geração livre de texto, análise de mídia, criação/manutenção de um gateway Baileys próprio, alterações no código da Evolution API e qualquer compilação Native Image/GraalVM. A mudança documentada não autoriza implementação ou deploy.

## 2. Base real observada no projeto

O repositório existente usa TypeScript/Node, Baileys `7.0.0-rc14`, `better-sqlite3`, `node-cron`, Docker e testes com `node:test`. Os módulos atuais principais são `src/whatsapp.ts`, `src/notify-handler.ts`, `src/watcher-state.ts`, `src/notifier.ts`, `src/config.ts` e `src/scheduler.ts`.

- `chat_state` armazena arquivamento, grupo, nome e política `WORK_ONLY`/`ANYTIME`, derivada de nome/regex; `contact_identity` resolve LID/PN/JID.
- `pending_notification` guarda contagens e **`last_message_preview`**; `processed_message` guarda hashes para deduplicação.
- O código coleta somente mensagens `notify` recebidas, elegíveis e com estado arquivado conhecido; ignora mensagens do próprio usuário, status e eventos de protocolo.
- O fluxo legado aplica janelas de expediente para `WORK_ONLY`, agrega pendências e envia um webhook autenticado ao n8n; o n8n entrega no Telegram.
- `watcher.db` já utiliza WAL. Os testes cobrem o fluxo legado, identidade, grupos, deduplicação, arquivamento e filtragem.
- O README está parcialmente desatualizado quanto a contato único/armazenamento: **não** usar sua descrição como fonte de verdade sobre a versão atual.

Na migração, preservar o watcher TypeScript/Baileys atual e suas tabelas sem sobrescrita, até que o conjunto Evolution API + Spring seja testado e ativado explicitamente. O Baileys continua existindo **internamente na Evolution API**, mas deixa de ser uma dependência que manteremos diretamente no sistema novo.

## 3. Alternativas e arquitetura escolhida

**Escolha: Evolution API + Spring Boot 4.x, Java 25 LTS, JVM convencional.** O Spring concentra o valor de portfólio Java (regras, IA, autorização, auditoria, administração), e a integração não oficial com WhatsApp é responsabilidade de uma aplicação pronta, em vez de um gateway TypeScript autoral.

**Ordem obrigatória de adoção:**
1. **Primeira opção: Evolution API oficial**, repositório https://github.com/evolution-foundation/evolution-api. Testar uma versão **fixada**, conferindo o comportamento real do release/commit adotado, e nunca depender de `latest` sem validação.
2. **Fallback condicionado: fork de AlmogCohen**, https://github.com/AlmogCohen/evolution-api, branch `fork/2.3.7` (fixar commit ao implementar). Ele anuncia suporte a `archived`, `pinned` e `muteEndTime` em webhooks `chats.update`, `chats.set` e `chats.upsert`, bem como melhorias de sincronização e de mapeamentos LID/PN. **Não é release oficial, nem garantia de compatibilidade**; testar e auditar alterações antes do uso real.
3. **Se as duas opções falharem nos critérios essenciais:** **bloquear a migração**, documentar o motivo, manter o watcher atual operando e decidir separadamente como resolver. **Não** criar gateway Baileys próprio como fallback implícito desta spec.

**Motivo para essa sequência:** a issue oficial https://github.com/evolution-foundation/evolution-api/issues/2743 documenta que a Evolution API **2.3.7** perde o campo `archived` no caminho de `chats.update` e de sincronização histórica. A issue https://github.com/evolution-foundation/evolution-api/issues/2495 trata de outra operação: solicitar o arquivamento via API. A correção desse endpoint (PR https://github.com/evolution-foundation/evolution-api/pull/2515, mergeada na `develop`) **não comprova** que o estado arquivado passou a aparecer no webhook da versão oficial escolhida. Não supor resolução só pelo status de PR/issue.

**Risco operacional de `archiveChat` (relato externo de 2026-10-19):** em https://github.com/evolution-foundation/evolution-api/issues/2495#issuecomment-6273731128, o autor do bug relata que, em duas semanas de testes, 15 das 23 desconexões de dispositivo ocorreram após chamadas a `archiveChat`, em geral dentro de 2–4 segundos, com `401 conflict / device_removed`. É uma **correlação observada, não comprovação de causalidade**, mas o impacto possível na sessão pessoal é alto. **Fica proibido chamar `POST /chat/archiveChat/{instanceName}` ou usar `Baileys chatModify` para arquivar/desarquivar chats neste projeto, tanto na POC quanto na automação nova.** O objetivo é **ler** o estado de arquivamento definido manualmente no cliente WhatsApp. Não testar ativamente esse endpoint nem na conta/sessão produtiva. Se futuramente existir necessidade de alterar arquivamento remotamente, exigir um novo design e aprovação específica, com evidência de segurança da versão escolhida.

**Limitação expressa do fork:** o projeto afirma corrigir **o webhook** do problema #2743, **não** a persistência do estado `archived` no modelo Prisma/PostgreSQL da Evolution API. Portanto, o Spring precisa persistir o próprio estado verificado dos chats, e a sincronização/reconciliação inicial e após reinício deve ser comprovada em teste. Não inventar `archived=false` quando não há informação.

```text
                       [WhatsApp pessoal]
                                |
                          instância de testes
                                v
                  [Evolution API: oficial primeiro]
                        |   fallback após falha comprovada
                        +-> [fork AlmogCohen, sob validação]
                                |
                       Webhooks HTTP internos
                                v
             [Spring Boot 4.x / Java 25 / JVM convencional]
                 - recepção validada e inbox idempotente
                 - snapshots archived e identidade LID/PN/JID
                 - regras, debounce, perfis, disponibilidade
                 - Shadow Mode, Gemini/OpenRouter e auditoria
                 - Telegram admin e emissão de relatórios
                                |
                                v
                       [n8n] -> [Telegram]

  PRODUÇÃO ATUAL (intocada até cutover explícito):
  [Watcher TypeScript + Baileys] -> [n8n] -> [Telegram]
```

**Critérios obrigatórios da POC antes de escolher implementação:**
- Verificar **`archived=true` e `archived=false` reais** quando uma conversa é arquivada/desarquivada **manualmente no cliente WhatsApp**, sem chamar `archiveChat` ou `chatModify` via API. Verificar `chats.update` e eventos/snapshot inicial `chats.set` / `chats.upsert` conforme disponíveis.
- Verificar histórico/sincronização inicial com chats **já arquivados**, inclusive chats que já existem no banco da Evolution, e comportamento depois de reiniciar, desconectar e reconectar. Sem snapshot completo, estabelecer mecanismo **realmente verificável** de bootstrap/reconciliação; do contrário o critério falha.
- Validar DM, grupos, mensagens novas e eventos descartáveis, `@lid`, PN/JID, alias e mudança de nome, deduplicação, ordenação, timestamps e suporte aos eventos necessários à detecção de referência pessoal e replies.
- Testar ao vivo o envio/recepção com instância isolada e verificar compatibilidade e impactos da coexistência de dispositivos vinculados. Medir estabilidade e motivos de desconexão (`401 conflict / device_removed`, se ocorrer), com e sem atualizações **manuais** de arquivamento, e verificar por logs que não houve nenhuma chamada de escrita ao endpoint `archiveChat`. Não trocar automaticamente a sessão ou as credenciais da produção; não desvincular o watcher existente para uma POC.
- Confirmar autenticação, forma de entrega, retries e tratamento de erro dos **webhooks da versão escolhida**. **Não presumir** outbox durável, `eventId` nativo, assinatura HMAC, garantia de entrega pelo menos uma vez ou ordem por chat se o produto não documentar/demonstrar essas capacidades.
- Verificar segurança e dependências do fork: origem exata do código, build reproduzível, commit fixo, compatibilidade de versão, alterações de privacidade e exposição de API. Caso seja escolhido, registrar explicitamente que a manutenção é de terceiro não oficial.

**Infraestrutura:** usar Docker Compose em rede privada. A Evolution API v2 usa PostgreSQL e Redis, conforme documentação oficial https://github.com/evolution-foundation/docs-evolution/blob/main/v2/en/install/docker.mdx. **Reutilizar a instância PostgreSQL existente na VPS** para Evolution e Spring, com **bancos lógicos, usuários e permissões próprios** para cada aplicação, sem criar outro servidor PostgreSQL por padrão. Validar disponibilidade, compatibilidade, pool total, backup/restauração e rede interna; providenciar Redis compatível para Evolution, com isolamento. Não adicionar Kafka, RabbitMQ, Kubernetes ou gateway TypeScript próprio no MVP.

**Mensageria:** começar com **webhook HTTP Evolution API → Spring Boot → inbox/jobs/outbox PostgreSQL**. O PostgreSQL provê fila durável interna depois do evento recebido. Isso não recupera eventos perdidos antes de chegarem ao Spring. A Evolution API suporta saídas RabbitMQ e Kafka conforme configuração do projeto (https://github.com/evolution-foundation/evolution-api/blob/main/.env.example), mas **nenhum broker deve ser incluído sem necessidade demonstrada**. Se a POC comprovar falha de confiabilidade no transporte HTTP e a distribuição selecionada expuser os eventos corretos no broker, comparar **RabbitMQ primeiro** (publisher confirms, durable queues e manual consumer acks: https://www.rabbitmq.com/docs/confirms), antes de Kafka (replay/múltiplos consumidores/alto volume, não previstos para conta pessoal: https://kafka.apache.org/40/design/design/). Testar na versão escolhida tanto as garantias reais de entrega quanto preservação de `archived`, reconexão e LID/PN. Broker não corrige `archived` descartado pela Evolution antes da publicação.

**Contrato e segurança:** o Spring expõe receptor interno de webhooks; valida tamanho, schema, instância, origem e mecanismo de autenticação **efetivamente configurável na versão testada**. Se a Evolution não fornecer autenticação adequada por evento, restringir exposição pela rede Docker e interpor proxy de ingress com autenticação quando necessário, sem fingir que HMAC existe nativamente. Definir chave idempotente composta a partir de identificadores **estáveis e presentes** no payload (ou de derivação testada), persistir inbox antes de responder sucesso e tratar redelivery. Estados de arquivamento fora de ordem ou desconhecidos bloqueiam ações; reconciliar em vez de inferir. Identificar lacunas irrecuperáveis e suspender o motor real.

**Ownership:** a Evolution API é dona da sessão WhatsApp e da infraestrutura de integração **apenas após cutover**; o Spring é dono das políticas, IA, simulação, decisões, Telegram admin e estado operacional da automação. O watcher TypeScript permanece dono do comportamento produtivo até migração deliberada. O Shadow Mode não envia respostas ao WhatsApp nem avisos individuais simulados, ressalvados relatório diário e comandos administrativos via n8n.

**Build do núcleo Java:** Maven + JAR JVM + Docker multi-stage. **Sem GraalVM, Native Image, Spring AOT ou compilação nativa.** Avaliar memória/CPU reais para ajustar heap e capacidade. Spring Boot 4.x com Java 25 conforme https://docs.spring.io/spring-boot/system-requirements.html. ASP.NET Core Minimal API e Quarkus deixam de ser opções para esta implementação, sem impedir revisão futura.

**Cutover/rollback:** testar a Evolution oficial primeiro, ativar o fork **somente se** testes demonstrarem problema impeditivo com a oficial e o fork cumprir a matriz. Antes da migração, provar paridade de estados `archived`, entrega, identidades, custos, falhas e reconexão. Fazer corte controlado evitando notificações duplicadas entre integrações WhatsApp/n8n, mantendo backup de credenciais e rollback documentado. Caso algum critério falhe, manter a produção em TypeScript, sem degradação silenciosa.

## 4. Escopo e filtragem de mensagens

- Monitorar **apenas conversas com `archived=true`** no instante da recepção. Desarquivar torna a conversa inelegível para novas decisões; arquivar novamente torna-a elegível.
- Abranger contatos privados e grupos arquivados. O Shadow Mode é global para todos eles, sem importar regras que bloqueiam ações; registrar ações que *teriam* sido negadas.
- Processar **mensagens novas**, não varrer/reprocessar histórico indiscriminadamente. A Evolution API fornece eventos/snapshots conforme a distribuição testada; o Spring reconcilia e registra o estado de arquivamento conhecido no instante do evento. Ausência, defasagem, lacuna na sincronização ou conflito de estado causa descarte seguro com motivo auditável.
- Identidade estável: consumir e validar os mapeamentos LID/PN/JID da Evolution API escolhida, mantendo identidade canônica e aliases no Spring para preservar overrides mesmo quando o nome muda. Se não houver mapeamento, não inventar telefone.
- Deduplicar no Spring por identificadores reais de evento/mensagem da Evolution API (ou chave composta testada), sem supor que exista `eventId` nativo. Não alterar a deduplicação do legado. Eventos atrasados não podem sobrescrever estados mais novos.
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

1. A **Evolution API oficial**, ou somente se necessário o **fork AlmogCohen validado**, recebe eventos WhatsApp, envia os webhooks configurados ao receptor HTTP privado do Spring e mantém sessão/infra de integração próprias. Durante a POC o watcher TypeScript produtivo continua independente.
2. Spring valida o formato, a identidade da instância e a autenticação configurada/protegida no ingress. A partir de dados realmente existentes, constrói chave idempotente, persiste `inbox_events` no PostgreSQL e só confirma o HTTP após commit. Duplicatas recebem ACK idempotente. **Não há garantia presumida** de webhook persistente ou retries pela Evolution; verificar e registrar o comportamento.
3. Atualiza snapshot e aliases, aplicando o estado `archived` somente quando conhecido e conciliável. Reconcilia sincronização inicial e pós-reconexão; se não houver maneira confiável de provar o estado, falha fechada e não dispara nova ação real.
4. Processa mensagens novas de chats arquivados com debounce de **10s de inatividade** e **30s máximos** por lote, separando remetentes de grupo.
5. Resolve `global → profile → override`, disponibilidade e gatilhos; classifica por `ClassificationService` quando o filtro determinístico pedir IA.
6. Valida resultado do modelo, autorização explícita, template, orçamento, cooldown e kill switch **fora da IA**. Persiste `Decision` (`wouldAlert`, `wouldReply`, `templateId`, `decisionReason`, `blockedReason`) e auditoria, agregando métricas para relatórios.
7. No Shadow Mode não existe envio WhatsApp. Futuramente, envio real somente pela **API REST da Evolution escolhida**, via comando autenticado do Spring com chave de idempotência e confirmação/observação de resultado; n8n continua roteando alertas e relatórios ao Telegram.

**Fail-closed:** payload ausente/inválido, webhook inesperado, autenticação rejeitada, duplicidade conflitante, lacuna de eventos, estado de arquivamento incerto, perda de sincronização, timeout, falha de persistência, provedor IA indisponível, privacidade incompatível, baixa confiança ou orçamento excedido impedem novas ações reais. Classificação incerta gera `UNCERTAIN`, sem fallback por discordância subjetiva.

**Entrega confiável:** transação de recebimento persiste inbox/jobs no PostgreSQL; worker do próprio Spring processa por `FOR UPDATE SKIP LOCKED`, lease expirável, retries/backoff e falhas auditáveis; outbox PostgreSQL protege a passagem de decisão para tentativa de envio. Confirmar webhook HTTP apenas após commit. Não manter transação SQL aberta durante IA/requisição externa. Para webhook perdido, verificar retry/reconciliação da Evolution na distribuição efetiva; **não inventar** uma outbox dentro da Evolution. Se eventos essenciais não forem recuperáveis, reprovar POC. Sem broker adicional no MVP.

**Isolamento:** falhas da Evolution API ou do Spring durante testes não podem alterar fluxo, dados, credenciais ou notificação do watcher legado. Nenhuma migração real sem checklist de rollback.

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
- Diferenciar explicitamente retenção do **PostgreSQL do Spring**, do SQLite legado, dos bancos da Evolution, do n8n, do Telegram e dos serviços de IA. A purga em qualquer deles **não apaga** cópias já transmitidas a terceiros.

Referências oficiais verificadas em 2026-10-16:
- Gemini API pricing/data usage: https://ai.google.dev/gemini-api/docs/pricing
- OpenRouter route privacy controls: https://openrouter.ai/docs/guides/get-started/sovereign-ai
- OpenRouter guardrails: https://openrouter.ai/docs/guides/features/guardrails/overview
- Modelo secundário: https://openrouter.ai/anthropic/claude-haiku-5.5

## 8. Persistência, migração e retenção

**Novo Spring Boot: PostgreSQL obrigatório, sem SQLite.** Reutilizar a instância PostgreSQL já instalada na VPS, criando **banco lógico e usuário próprios** para a automação. O Spring não pode acessar/escrever nas tabelas da Evolution. Configurar HikariCP com pool pequeno dimensionado à VPS, PostgreSQL JDBC, migrações SQL com **Flyway**, backup e restauração testada, métricas e políticas de retenção. Preferir `JdbcClient`/Spring Data JDBC nas filas; JPA pode ser usado para domínio se simplificar. Validar com PostgreSQL real em testes (Testcontainers). Referências: https://docs.spring.io/spring-boot/reference/data/sql.html e https://docs.spring.io/spring-boot/how-to/data-initialization.html.

**Propriedade dos bancos:**
- **Watcher TypeScript legado:** mantém `watcher.db` SQLite existente intacto até cutover autorizado; não migrar/deletar automaticamente.
- **Evolution API oficial ou fork:** mantém seu próprio **banco PostgreSQL** e Redis/sessões/volumes, isolados. Pode usar a **mesma instância PostgreSQL física da VPS**, mas banco, usuário e permissões diferentes dos do Spring. A correção de webhook `archived` do fork **não** inclui armazenamento desse campo no Prisma/PostgreSQL da Evolution.
- **Spring Boot:** persiste estado de arquivamento verificado, identidades, regras, simulações, operações e jobs em banco **PostgreSQL exclusivo**. Sem leitura/escrita cruzada nos bancos da Evolution ou do legado.

| Domínio Spring/PostgreSQL | Responsabilidade |
| --- | --- |
| `inbox_events` | Eventos recebidos e deduplicados (`UNIQUE` por chave estável testada), metadata de origem e ingestão |
| `processing_jobs` | Jobs duráveis, execução agendada, lease expirável, tentativas/backoff e falhas inspecionáveis |
| `outbox_actions` | Operações n8n/Telegram e futuros comandos WhatsApp, tentativas e chaves de idempotência |
| `chat_snapshot` e `identity_aliases` | Estado `archived` conhecido e mapeamentos LID/PN/JID |
| `profiles` e `chat_policies` | Perfis, políticas e overrides por identidade |
| `response_templates` | Templates versionados e autorizações explícitas |
| `holiday_rules` | Feriados, pontos facultativos, fonte/validade |
| `simulation_decisions` | Auditoria, decisões, modelos e custos |
| `system_settings` | Flags, kill switch, orçamentos e limites não secretos |

**Fila dentro do PostgreSQL:** receptor valida webhook e persiste `inbox_events` e trabalho pendente em transação, confirmando HTTP somente após commit. Workers do Spring reivindicam lotes pequenos via `SELECT ... FOR UPDATE SKIP LOCKED`, alteram `status` e `locked_until` em transação curta, executam IA/regras fora do lock e concluem condicionados ao lease. Ao cair o processo, jobs abandonados voltam a ser elegíveis após timeout; retries com backoff, limite de tentativas e dead-letter lógica. Saídas são registradas em `outbox_actions` na mesma transação da decisão, reduzindo perda entre decisão e tentativa de envio. **Idempotência é obrigatória**; não prometer exatamente uma entrega nos serviços externos.

**Sem broker no MVP:** PostgreSQL é a fila durável interna, com polling de worker. `LISTEN/NOTIFY` pode acordar workers, mas não substituir os dados persistidos/polling de recuperação: https://www.postgresql.org/docs/current/sql-listen.html. `SKIP LOCKED` foi projetado também para cenários de consumidores concorrentes de tabelas tipo fila: https://www.postgresql.org/docs/current/sql-select.html.

**Migração:** exportar e reconciliar perfis e identidades do watcher existente, sem migrar só por nome exibido. Não compartilhar sessão Baileys legada com Evolution sem procedimento validado. Reconciliar estado `archived` efetivamente recebido na POC, preservar rollback e não duplicar notificações no n8n.

**Retenção:** trechos mínimos e sanitizados **até 7 dias**, incluindo dados sensíveis eventualmente em inbox, processing_jobs e outbox, com purga automática testada. Metadados sem conteúdo por padrão **90 dias**, configuráveis. Cobrir `pending_notification.last_message_preview` legado sem quebrar suas filas. A limpeza Spring **não remove** textos persistidos pela própria Evolution, logs ou backups; revisar retenção desses recursos separadamente.

Segredos e credenciais em configuração protegida, não em tabelas de domínio. Isolar acessos, volumes, usuários, backups e conexões.

## 9. Telegram e n8n

**MVP Telegram:** comandos rápidos (`/status`, `/ocupado`, `/disponivel`, `/ferias`, `/simulacao`, `/relatorio`, `/emergencia`) e menus com botões para perfis, conversas, grupos, templates, pontos facultativos, orçamento, modo simulado e autorizações. Respostas administrativas e resumos usam o n8n para envio; o Watcher não deve duplicar o cliente de mensagens Telegram sem necessidade.

O Spring Boot recebe `getUpdates` por long polling como **único consumidor de updates** da conta do bot. Se houver webhook de entrada nesse bot, não ativar polling simultaneamente. Caso o menu exija reconhecer callbacks por API, manter esse trecho limitado e documentado, sem mover o sistema de dispatch do n8n para o código Java.

**Segurança:** administrador único por `TELEGRAM_ADMIN_USER_ID`, `TELEGRAM_ADMIN_CHAT_ID` e tipo de chat privado; validar autor, chat e callback em toda operação. Confirmar operações destrutivas, mudança de regras globais e ativação de envios reais. Botões antigos, expirados e já consumidos são inválidos. Auditoria de comandos sem segredos. `/emergencia` tem prioridade e funciona sem IA.

**Contrato com n8n:** manter `WhatsApp Archived Watcher - Telegram Digest` ativo e seu webhook autenticado. Acrescentar somente suporte retrocompatível a novos tipos de payload (`simulation_daily_summary`, `intelligent_alert` e interações de UI necessárias), mantendo o papel de transporte. A formatação e a escolha da ação pertencem ao Spring, mas o envio Telegram permanece no workflow; verificar quais ajustes pontuais no node `Montar mensagem` serão necessários antes da implementação. Não quebrar `archived_messages` nem alertas de reconexão.

**Relatório do Shadow Mode:** resumo diário em horário configurável (mensagens candidatas, chamadas IA, solicitações, críticos, bloqueios, custos, erros e simulações de resposta); `/relatorio` com filtros e detalhes. Não enviar automaticamente cada evento simulado. O resumo diário é mensagem administrativa real, explicitamente autorizada, sem conteúdo de mensagem bruta.

**Next.js SSR (futuro):** adicionar API autenticada sobre serviços Spring já existentes, sem API pública/painel nesta fase.

## 10. Estratégia de testes e aceitação

Cobertura automatizada de unidade, contrato HTTP e integração (fixtures/webhooks da Evolution API oficial e do fork, fakes para Gemini, OpenRouter, n8n e Telegram e **PostgreSQL real com Testcontainers** em testes), com casos:
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
- Gate de POC **com a Evolution API oficial primeiro**: `archived` inicial, updates **originados por ações manuais no WhatsApp**, desarquivamento, reconexão, contatos e grupos, chats já conhecidos do banco, mensagens fora de ordem e identidades LID/PN; repetir a matriz no fork somente se a oficial falhar. Confirmar **zero chamadas** a `archiveChat`/`chatModify` e registrar desconexões e `401 device_removed` como falhas de estabilidade. Estado `archived` mantido apenas localmente, sem sincronização real verificável com WhatsApp, **não atende** ao requisito de monitorar os chats efetivamente arquivados.
- Verificar autenticação **real** do webhook e restrição de rede; payload inválido, replay quando possível, duplicidade, ACK somente após **commit PostgreSQL**, queda do Java e eventuais retries realmente fornecidos pela Evolution. Sem outbox presumida na Evolution. Se perder evento essencial sem recuperação confiável, reprovar a POC.
- PostgreSQL existente com usuários/bancos distintos; migrations Flyway; worker `SKIP LOCKED` concorrente, leases expirados, retry, dead-letter, rollback e outbox idempotente com Testcontainers. Broker RabbitMQ somente se justificar por falha concreta de transporte.
- Watcher legado mantém notificações durante toda POC e Shadow Mode; comparar resultados, evitar duplicidade no cutover, preservar sessão e testar rollback. Nem oficial nem fork podem ser promovidos sem critérios funcionais e de segurança.
- Spring Boot 4.x/Java 25 executa em JAR JVM/container sem GraalVM, Native Image ou pipeline AOT; medir consumo na VPS.

**Critério de liberação:** validar a distribuição Evolution API escolhida (oficial prioritária; fork somente após falha demonstrada), depois Shadow Mode; revisar falsos positivos/negativos e custos reais; liberar ações por opt-in de perfil/conversa/situação e com kill switch testado. A integração por Evolution API continua baseada em tecnologia **não oficial do WhatsApp** e envolve risco de restrição da conta, não eliminado pelas permissões locais.

## 11. Incrementos sugeridos (ainda não são plano de implementação)

1. **POC isolada da Evolution API oficial:** fixar release/commit, preparar Docker Compose com PostgreSQL/Redis conforme requisitos, instância de teste e rede privada; avaliar webhook/snapshot `archived`, reconexão, sincronização inicial, LID/PN, grupos, latência e memória. Preservar 100% do watcher TypeScript produtivo.
2. **Fallback explícito:** se a oficial falhar em critério obrigatório, documentar evidência e testar o fork https://github.com/AlmogCohen/evolution-api (`fork/2.3.7` fixada em commit) com **a mesma matriz**; validar segurança/build, sem confundir suporte a webhook com persistência em PostgreSQL. Se também falhar, interromper migração e manter o legado.
3. **Núcleo Spring Boot 4.x / Java 25 na JVM:** Docker JVM, **PostgreSQL existente da VPS** com banco/usuário exclusivos e Flyway, webhooks, inbox/jobs/outbox transacionais, worker `FOR UPDATE SKIP LOCKED`, snapshot/reconciliação; sem Native Image, GraalVM, AOT, Kafka, RabbitMQ obrigatório ou gateway próprio de Baileys.
4. **Shadow Mode:** debounce, perfis, disponibilidade/feriados, políticas, auditoria, segurança e testes fail-closed; sem envio WhatsApp e sem alertas simulados individuais.
5. **IA e privacidade:** Gemini/OpenRouter, orçamento, filtros de dados, retenção, comparação de resultados e relatórios.
6. **Administração Telegram via Spring:** comandos privados, menus, auditoria e relatório diário entregue pelo n8n preservando payload legado.
7. **Cutover reversível:** comparar comportamento dos serviços, documentar migração da sessão, evitar duplicação no n8n, testar rollback e somente então permitir recursos reais mediante opt-in/kill switch. Next.js SSR fica para fase futura.

**Não implementar ou implantar esta arquitetura até aprovação explícita do plano de execução.**

## 12. Aprovação e próximo gate

Este documento registra as decisões do brainstorming e a revisão para **Evolution API oficial primeiro, fork AlmogCohen como fallback condicionado, Spring Boot 4.x na JVM, PostgreSQL existente da VPS com bancos isolados e jobs duráveis no PostgreSQL, sem GraalVM/AOT nativo nem gateway Baileys próprio**. O proprietário deve revisar e aprovar **esta especificação escrita**, inclusive os defaults propostos (cooldown inicial de 60 minutos, retenção de metadados de 90 dias, limites de fila e necessidade de configurar orçamento). Só então iniciar `superpowers:writing-plans` para produzir o plano executável. **Nenhum código de produção é autorizado por esta spec isoladamente.**

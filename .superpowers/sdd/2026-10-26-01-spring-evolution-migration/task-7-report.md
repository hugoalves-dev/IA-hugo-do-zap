# Tarefa 7 — integração, evidências e rollback

## Estado entregue

- Implementado `EvolutionJobProcessor`: reivindica jobs com lease, mapeia `messages.upsert`, chama `MessageService`, conclui jobs e reprograma falhas transitórias com backoff.
- Eventos `chats.*` são gravados em quarentena como `CHAT_STATE`, sem ID fabricado e sem alterar `chat_snapshot`. O campo `archived` no webhook não é aplicado.
- O worker só processa mensagens com `type=notify`, `key.fromMe` booleano, mensagem estruturada, timestamp e JID. Metadados ausentes são postos em quarentena.
- Integração local cobre webhook HTTP → inbox → job → `MessageService` → pendência → outbox → fake n8n local. Inclui duplicata, lease expirado/restart, falha temporária de banco, snapshot desconhecido, falha HTTP e retry.
- Nenhum pareamento, alteração de VPS, cutover, remoção de TypeScript ou `compose up` foi executado.

## Versões e artefatos confirmados

- Versão Java declarada em `pom.xml`: `1.0.0-SNAPSHOT`.
- Commit base informado para este worktree: `61632be99c6dfeddd28690ebf15c144bb45aa08f`.
- Commit local de implementação e testes: `f8ed4e74c993a40e40a7e780d781caa78f9da31a`.
- Imagem construída localmente: `watcher-evolution-task7:local`.
- ID/digest local reportado por `docker image inspect`: `sha256:09c679bcb67cbfeb697c5585bccab167df755733e6be2c8ade871b0f4237f5c3`.
- Não houve push; não há digest de registry.
- A versão, commit e digest da Evolution em produção não estão fixados nem confirmados neste worktree.

## Verificações executadas

- Comando: `mvn '-Dtest=EvolutionPipelineIntegrationTest,EvolutionWebhookControllerTest,EvolutionWebhookControllerHttpTest,JobRepositoryTest,EvolutionEventMapperTest,MessageServiceTest,ChatStateServiceTest,NotificationServiceTest' test`.
- Resultado: `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- O teste de quarentena primeiro falhou como esperado: `expected: <202> but was: <400>`. Após a implementação, passou e confirmou `source_event_id is null` e nenhum snapshot criado.
- `docker build` local concluiu com sucesso usando contexto temporário restrito a `Dockerfile`, `pom.xml` e `src/main`; não enviou `.idea/` ou `target/`.
- `git diff --check` concluiu sem erros.
- CódigoGraph indexou os oito arquivos Java de fluxo selecionados. `get_edit_context` não encontrou símbolos no índice; a análise seguiu por busca textual e leitura direta, sem ampliar a busca ao repositório inteiro.

## Revisão de retenção, leases e entrega n8n (2026-10-27)

- A revisão confirmou que a versão anterior mantinha `inbox_events.payload` indefinidamente e podia reivindicar 20 jobs com lease de 2 minutos antes do processamento serial.
- A migração V9 define deadline em 6 dias, 23 horas e 58 minutos para dar margem ao purge periódico de 60 segundos, mantendo payload por no máximo 7 dias se o serviço estiver operando. `RetentionService` substitui o payload vencido por `{}`, mantém apenas a chave de deduplicação e metadados mínimos da inbox, e finaliza como `EXPIRED` os jobs vinculados, limpando leases. `JdbcJobRepository` não reivindica job cuja inbox já venceu; o worker também verifica a expiração imediatamente antes de ler o payload.
- Limite operacional: se a aplicação ficar indisponível por mais de 7 dias, o banco não executa purge por conta própria; os payloads vencidos ficam sem processamento (claim filtrado) e são limpos na primeira execução do serviço após retorno. Um SLA estrito de apagamento mesmo com aplicação parada exige scheduler externo/DB comprovado, inexistente nesta configuração.
- O worker agora reivindica um job por vez, no máximo 20 por passagem. Teste PostgreSQL concorrente com dois workers confirmou um claim inicial por worker. A simulação da estratégia anterior (claim em lote de 20) falhou como esperado: um worker retornou 0 jobs no lugar de 1.
- Teste PostgreSQL adicional força expiração depois do claim e antes da leitura; o worker finaliza o job como `EXPIRED` e não cria mensagem processada.
- O fake HTTP n8n lê e registra o corpo da requisição antes de atrasar as respostas. O teste demonstra 3 requisições aceitas pelo fake e 3 timeouts do cliente durante o retry, sem cabeçalho `Idempotency-Key`. Portanto, entrega permanece **at-least-once**: pode haver duplicata quando o destino aceita e a resposta se perde/atrasa. Não há contrato de idempotência n8n confirmado nem alegação de exactly-once.
- RED/GREEN adicional: teste de retenção falhou antes com 0 payloads limpos (esperado 3); teste de claim vencido falhou antes (`expected true, was false`); teste de concorrência falhou ao simular lote antigo (`expected 1, was 0`). Depois das correções, o comando `mvn '-Dtest=RetentionServiceTest,JobRepositoryTest,EvolutionPipelineIntegrationTest,N8nClientTest,RetentionRegressionTest' test` terminou com `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`, em PostgreSQL via Testcontainers e fake n8n local.
- Commit local desta revisão: `20ff7fb` (`Bound inbox retention and serialize webhook job claims`). O relatório foi atualizado em commit documental subsequente.

## Checklist adicional de revisão e rollback

- [ ] Revisar janela de retenção de 7 dias e metadados mínimos preservados para deduplicação; para garantia de apagamento durante indisponibilidade prolongada, provisionar e validar purge externo independente da aplicação.
- [ ] Confirmar política de retry do consumidor e deduplicação no downstream; resposta HTTP perdida após aceitação pode gerar envio duplicado ao n8n.
- [ ] Antes de rollback binário, manter a coluna `expires_at` e seus defaults compatíveis; não reter payload bruto expirado nem reabrir jobs `EXPIRED` sem decisão operacional e replay revisado.
- [ ] Tratar qualquer adoção de chave de idempotência n8n como mudança de contrato a validar separadamente com o workflow real; nenhum cabeçalho ou suporte foi inventado nesta tarefa.

## Evidência que falta antes de ativar

- Não há payload capturado e sanitizado da versão exata da Evolution que será usada, nem versão/commit/digest dessa imagem. O fixture existente é sintético e declara essa limitação em `src/test/resources/fixtures/evolution/README.md`.
- O teste do pipeline usa `type=notify`, `key.fromMe`, `message` e timestamp sintéticos. Esses campos ainda precisam ser validados contra a versão fixada antes de habilitar o consumidor em produção.
- O snapshot arquivado do teste é semeado por `ChatStateService` com flags explícitas de verificação; isso não prova bootstrap inicial nem snapshot após reconexão em runtime.
- Testes existentes cobrem valores booleanos `archived`, política de grupos, pares LID/PN explícitos e sinais READ sintéticos. Eles não provam que os eventos reais da Evolution fornecem esses dados corretamente.
- A transação webhook→inbox/job, duplicata e retorno 503 em falha de banco têm testes locais. Não há evidência da política de retry ou perda de webhook do serviço Evolution em produção.
- `archived=true`, `archived=false`, grupos, LID/PN, READ e reconexão precisam de fixture real revisada, sanitizada e vinculada ao release antes de qualquer ativação.

## Checklist para revisão antes de cutover

- [ ] Fixar e registrar versão, commit e digest da Evolution e do artefato Java candidato.
- [ ] Capturar e revisar payloads sanitizados do release fixado para `messages.upsert`, `chats.*`, grupos, LID/PN e reconexão.
- [ ] Confirmar bootstrap inicial e novo snapshot sincronizado após reconexão; comparar JIDs, nomes, flags arquivadas e contagens com a fonte legada.
- [ ] Validar manualmente casos `archived=true` e `archived=false`; verificar que READ só altera estado quando evento/status estiver provado.
- [ ] Simular webhook duplicado, timeout, perda de resposta e indisponibilidade de banco; confirmar retry do emissor e deduplicação inbox/job.
- [ ] Comparar payload e contagem de notificações Java com a implementação legada para contatos e grupos, sem dupla entrega.
- [ ] Ensaiar backup e restauração do banco; registrar duração, contagens e integridade após restore.
- [ ] Confirmar exclusão mútua: somente um dispatcher de notificação habilitado durante cada etapa.
- [ ] Definir alertas para webhook 4xx/5xx, backlog e idade de jobs, quarentena por motivo, retries, outbox pendente e erros do n8n.
- [ ] Obter autorização separada antes de parear, alterar VPS ou iniciar cutover.

## Checklist de rollback

- [ ] Parar o consumer Java e confirmar que ele não reivindica jobs nem envia notificações.
- [ ] Confirmar que Java e TypeScript não estão ativos ao mesmo tempo; habilitar um único dispatcher.
- [ ] Restaurar a configuração de webhook anterior a partir do backup revisado, sem apagar inbox, quarentena ou outbox.
- [ ] Reprocessar ou reconciliar pendências com deduplicação; não reenviar notificações já confirmadas pelo n8n.
- [ ] Restaurar snapshot de banco somente se a comparação indicar corrupção; validar o restore antes de retomar entregas.
- [ ] Monitorar backlog, erros, duplicatas e contagem de notificações após rollback; interromper envio se houver divergência.
- [ ] Preservar TypeScript e seus testes até corte autorizado, validado e com rollback ensaiado.

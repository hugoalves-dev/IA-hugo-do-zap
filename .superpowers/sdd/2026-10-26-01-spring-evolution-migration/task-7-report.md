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

# Task 2 — validação determinística e ciclo de vida dos prompts

## Status

Implementação concluída, testada e commitada. Verificação focada final aprovada após o ajuste do teste de startup. Sem push ou implantação por este agente. O agente principal fará a revisão e o push autorizado após receber o commit.

## Commit

`efa0ca81d1bbfed4de575d82181fafd81d1e33e1` — `feat: validate and publish prompt revisions safely`.

O relatório é registrado em um commit documental subsequente.

## Implementação

Criadas `ClassificationPromptValidator`, `ClassificationPromptSnapshot` e `ClassificationPromptService`, com testes próprios. A validação é determinística, sem chamada a modelos. Rejeita texto vazio, mais de 16.000 caracteres, placeholders de runtime (nenhum permitido atualmente) e padrões explícitos de alteração das regras de segurança/autorização, schema JSON ou ações reais. Os códigos de violação são estáveis e a lista retornada é imutável.

O serviço publica o snapshot Java padrão como revisão zero, com hash SHA-256 do conteúdo UTF-8 e metadados determinísticos (`java_default`, `Java default operational prompt`, `Instant.EPOCH`). Rascunhos manuais copiam o prompt ativo e guardam sua revisão base; revisão base obsoleta é rejeitada na criação. Zero e null identificam o padrão nas entradas de ponteiro. O banco continua usando NULL para o padrão.

Rascunhos expiram após dez minutos, partes manuais têm limite de 4.096 caracteres e propostas/refinamentos têm limite de três. Quebras CRLF e CR são normalizadas para LF antes da persistência; partes são concatenadas com um LF. Preview registra o estado preview, fornece o corpo normalizado, os códigos de validação e contagem resumida de linhas adicionadas/removidas, sem ativar revisão.

Ativação usa uma TransactionTemplate sobre o mesmo DataSource das operações do repositório. A operação utiliza exclusivamente o Instant fornecido pelo chamador para a checagem de expiração e os registros de publicação. A leitura verifica proprietário e expiração, o serviço verifica estado editável, e a atualização final do rascunho compara a versão observada. Inserção da revisão, CAS do ponteiro ativo, inserção do histórico e alteração do rascunho para activated estão na mesma transação. Falha de qualquer CAS lança exceção e desfaz todos esses writes. Restauração reutiliza uma revisão existente; reset aponta para NULL e conserva revisões e histórico. Cancelamento respeita proprietário, estado, versão e expiração.

APIs de integração: `ValidationResult` é record público aninhado em `ClassificationPromptValidator`; `PromptPreview` e `HistoryEntry` são records públicos aninhados em `ClassificationPromptService`. O construtor Spring recebe `ClassificationPromptRepository`, `ClassificationPromptValidator` e `PlatformTransactionManager`. Entradas inválidas de conteúdo/metadados usam IllegalArgumentException; rascunho ausente/expirado/estrangeiro/encerrado ou CAS obsoleto usam IllegalStateException com código `prompt_state_conflict`.

## TDD e testes

- Os quatro testes obrigatórios do validador foram escritos antes da classe. A execução RED falhou na compilação pela classe ausente, conforme `/tmp/issue45-task2-validator-red.log`. Não foi uma falha de assertion: essa limitação do RED inicial é registrada explicitamente.
- Os testes obrigatórios do ciclo de vida foram escritos antes do serviço/snapshot. A execução RED falhou na compilação pela classe ausente, conforme `/tmp/issue45-task2-service-red.log`.
- GREEN do validador: quatro testes, zero falhas/erros/skips, `/tmp/issue45-task2-validator-green.log`.
- GREEN focado com PostgreSQL: 18 testes (9 de serviço, 4 do validador e 5 do repositório), zero falhas/erros/skips, `/tmp/issue45-task2-focused.log`.
- Acrescentado teste de concorrência real que pausa a ativação após inserir a revisão e permite uma edição do rascunho em outra conexão. A publicação deve perder o CAS da versão, desfazer revisão/ponteiro/histórico e preservar a edição concorrente.
- Suíte completa Maven executada com PostgreSQL: 607 testes, uma falha, zero erros/skips; `/tmp/issue45-task2-suite.log`. A única falha foi `SpringFlywayStartupTest.appliesFlywayMigrationsAutomaticallyDuringApplicationStartup:38`, que esperava a versão 54 e encontrou a versão 55 da migração já entregue na Task 1. Todos os outros 606 testes passaram, incluindo os 19 testes de prompts e o teste adicional de concorrência.
- Corrigida somente a expectativa desatualizada de startup, de 54 para 55. Nenhuma migração ou comportamento de produção foi alterado para resolver essa falha. O agente principal autorizou registrar a execução completa acima e repetir startup + suíte focada após a correção. Verificação final aprovada: 20 testes, zero falhas/erros/skips (startup, 10 de serviço, 4 de validação e 5 de repositório). Log: `/tmp/issue45-task2-final-focused.log`. Não foi executada uma segunda suíte completa após esse ajuste pontual.
- `git diff --cached --check` passou.

Comando de teste usa host Maven 3.9.11, JDK 25, truststore `/tmp/issue45-tools/cacerts`, settings `/tmp/issue45-settings.xml` e repositório Maven `/tmp/issue45-m2`. Os testes PostgreSQL usam acesso escalado autorizado ao socket Docker; nenhum banco de produção foi acessado.

## Limites e integração

- A validação textual é um lint conservador baseado em padrões, não uma prova semântica de segurança. A Task 3 deve manter as regras imutáveis do provider e a validação do schema independentemente do texto operacional. Os padrões podem rejeitar instruções benignas que contenham uma formulação negativa dos mesmos termos.
- A constante operacional contém as regras existentes de notificação, resposta, urgência e contexto. A Task 3 deve preservar no prefixo imutável as regras de entrada não confiável, identidade/autorização, evidências fixas e resposta JSON.
- O contrato existente de `saveProposal` do repositório incrementa o contador em toda proposta; o serviço mantém esse contrato, inclusive quando origin é manual. O fluxo de partes manuais não consome refinamentos.
- O histórico apresenta o autor e instante da transição; conteúdo/hash/origem/descrição pertencem à revisão escolhida, ou ao padrão quando nextRevision é NULL.
- O serviço conserva a semântica de CAS por identidade da revisão ativa. Ativação seguida de restauração à mesma revisão não altera uma geração de ponteiro, pois o contrato não inclui esse campo.
- Descrição padrão e hash são determinísticos. A data EPOCH identifica o padrão Java sem inventar uma data de criação persistida.

## Correção após revisão independente — confirmação vinculada à versão

A revisão identificou uma lacuna Important: a API anterior verificava a versão lida durante a própria ativação, mas não recebia a versão confirmada pelo administrador. Assim, preview do corpo A, edição para B e confirmação antiga publicavam B. Também identificou o Minor em que origin null causava NullPointerException.

Antes da correção, foram adicionados `stalePreviewConfirmationCannotPublishEditedBody` e `nullProposalOriginIsRejectedAsInvalidMetadata`. A execução RED observada teve dois testes e duas falhas de assertion, sem erros/skips: a ativação obsoleta não lançou exceção e origin null lançou NPE em vez de IllegalArgumentException. Log: `/tmp/issue45-task2-review-red.log`.

A assinatura pública agora é obrigatoriamente `activate(UUID draftId, long adminId, long expectedDraftVersion, Long expectedActiveRevision, Instant now)`. Não existe overload que omita expectedDraftVersion. `PromptPreview` passa a incluir `long draftVersion`, correspondente à versão depois da alteração de status para preview. O chamador deve guardar essa versão junto à confirmação e enviá-la na ativação. A transação compara a versão confirmada com o rascunho atual antes de criar qualquer revisão, mantendo também o CAS final para proteger uma edição concorrente posterior à leitura.

O teste verifica que uma confirmação antiga não altera revisão ativa, revisões nem histórico; a edição B permanece intacta. Um novo preview retorna outra versão e permite publicar B. Origin null agora é rejeitado como metadado inválido por IllegalArgumentException sem alterar o rascunho.

GREEN focado após a correção: 22 testes, zero falhas/erros/skips (12 de serviço, quatro de validador, cinco de repositório e um de startup), com PostgreSQL real através do acesso Docker autorizado. Log: `/tmp/issue45-task2-review-green.log`. `git diff --check` também passou. Não foi repetida a suíte completa neste loop, conforme escopo de verificação solicitado pelo agente principal.

Minor diferido: o resumo de linhas é baseado na contagem de conteúdo de linhas e pode indicar +0/-0 quando apenas a ordem das linhas muda. Isso não interfere no vínculo de versão, no corpo completo mostrado no preview, no hash ou nas garantias de publicação. O commit deste follow-up usa a mensagem `fix: bind prompt activation to confirmed draft version`; o hash é informado no retorno ao agente principal. Sem push por este agente.

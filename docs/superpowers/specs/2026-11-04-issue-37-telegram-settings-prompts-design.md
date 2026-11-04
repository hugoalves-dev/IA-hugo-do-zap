# Issue #37: configurações administrativas e versões do prompt

## Intenção

Permitir que o administrador autorizado consulte e opere, pelo Telegram privado, as configurações que o sistema realmente suporta. O mesmo catálogo de metadados poderá ser consumido depois pela Mini App da issue #18. A edição/versionamento do trecho operacional do prompt será auditável e ativa inicialmente apenas na classificação Shadow.

## Objetivos

- Organizar `/menu` em seções funcionais; não exibir ações sem implementação.
- Mostrar valor efetivo, origem, editabilidade, dependências e bloqueios, sem revelar segredos.
- Persistir em PostgreSQL apenas opções operacionais explicitamente whitelisted, com valor tipado, validação, versão, autor, horário, histórico e rollback.
- Preservar `WATCHER_SIMULATION_ENABLED=false` como teto: a interface pode pausar Shadow, mas nunca habilitá-lo acima do teto. Emergência, consentimento, orçamento, kill switch e flags de envio real permanecem gates independentes.
- Gerenciar revisões do trecho operacional do prompt, com comparação, validação, ativação e rollback. Registrar a revisão usada por decisão.
- Exibir política/autorização e motivos de bloqueio para conversas usando os repositórios de política e decisão existentes, sem duplicar a triagem ou alterar sua semântica.
- Oferecer `/contato <jid>` como consulta somente leitura de nome amigável, política efetiva/origem, disponibilidade atual, autorizações, consentimentos vigentes e bloqueios globais.
- Suportar callbacks de uso único/expiração, confirmação para mudanças de comportamento ou custo, autorização no handler, limitação de entrada e auditoria sanitizada.

## Fora de escopo

- Tornar todas as `@ConfigurationProperties` dinâmicas ou editar `.env`, Compose, credenciais, endpoints internos, flags reais ou controles de segurança pelo bot.
- Alterar a lógica semântica de triagem pertencente às issues #31/#33, emitir alertas ou respostas reais, reclassificar decisões antigas ou chamar um provedor pago durante testes automatizados.
- Criar uma SPA/Mini App nesta issue; o catálogo e os serviços serão reutilizáveis pela #18.

## Abordagens

1. **Escolhida — catálogo de configurações tipadas, armazenamento PostgreSQL versionado e serviço de revisões de prompt separado.** Reutiliza os caminhos de menu, callback, auditoria, política e persistência existentes; mantém limites explícitos e permite que uma futura Mini App consuma os mesmos contratos. O prompt customizável é sempre anexado a um núcleo imutável no servidor.
2. Adicionar comandos e SQL diretamente ao `AdminCommandService`. Menor mudança inicial, mas mistura transporte, persistência e regras; a issue #18 teria de duplicar a lógica.
3. Tornar o ambiente Spring inteiro dinâmico. Aumenta drasticamente a superfície de alteração e permitiria que valores operacionais mudassem gates que devem continuar sendo configuração de implantação; não atende o limite de segurança da issue.

## Contratos e limites

- O catálogo expõe apenas opções com definição explícita de tipo, faixa/enum, valor de ambiente/default e política de combinação. A primeira configuração global editável nova é `ai.context_window_hours`, inteira entre 1 e o teto configurado (máximo absoluto 168; default de ambiente 24). Opções já persistidas, como Shadow pausado, política, disponibilidade e orçamento, continuam nos repositórios e modelos existentes em vez de serem duplicadas. Segredos aparecem somente como `configurado`/`não configurado`.
- Um override não transforma uma configuração de ambiente restritiva em permissiva. O valor efetivo é calculado pela definição; indisponibilidade, configuração inválida ou conflito falham fechados.
- Revisões de prompt são imutáveis. O conteúdo editável é limitado a 4.000 caracteres e aos placeholders documentados. Ativação exige revisão existente e confirmação; rollback ativa uma revisão anterior sem apagar histórico. Apenas o núcleo fixo controla schema, conteúdo não confiável, segurança, privacidade, ferramentas, permissões, consentimento, orçamento, urgência crítica e ações reais.
- Uma decisão persiste o identificador/hash da revisão efetivamente montada. Decisões antigas nunca são reclassificadas.
- Uma ação de teste/calibração pelo Telegram usa fixture sintética sanitizada, confirmação do admin, Shadow ativo e a rota/limites/orçamento OpenRouter configurados. Como o fixture não contém dados de conversa, não consulta nem presume consentimento de contato. A fila marca a chamada antes do HTTP e nunca repete tentativa cujo resultado possa ser ambíguo. Testes automatizados usam transporte falso.
- A visão de contato resolve a identidade canônica, mas mostra apenas nome amigável (quando disponível), fontes da política e estados de autorização/consentimento; nunca expõe o JID canônico no resultado nem muda os gates.
- Todas as leituras e alterações administrativas passam pela autorização existente de usuário e chat privado. Callbacks contêm somente tokens opacos, são limitados a 64 bytes, consumidos uma vez e expiram. Alterações comportamentais/custo exigem confirmação explícita.

## Componentes afetados

- Telegram: `AdminCommandService`, `TelegramUpdateHandler`, `CallbackTokenService` e formatação/outbox existentes.
- Configuração/persistência: novos modelos de catálogo e repositórios, migrações Flyway aditivas; reuso de política, orçamento, disponibilidade e emergência atuais.
- IA: composição de prompt comum a OpenRouter/Gemini, revisão usada na decisão e testes de payload por transporte falso.
- Documentação: catálogo, limites, comandos e fluxos da interface.

## Critérios de aceitação

- O Telegram privado autorizado apresenta apenas seções implementadas e valores/origem/editabilidade/dependências/bloqueios aplicáveis; oferece retorno, paginação e restauração quando houver valor substituível.
- Shadow pode ser pausado/retomado respeitando o teto do ambiente e a emergência; o estado persiste e aparece em `/status`.
- Revisões operacionais podem ser criadas, consultadas, comparadas, ativadas e revertidas. Ambos os provedores recebem núcleo imutável mais versão ativa; o ID/hash usado fica associado à decisão.
- Texto longo/malicioso, placeholders inválidos, callbacks repetidos/expirados, concorrência, falha de banco, reboot e configuração restritiva não causam bypass nem perda de histórico.
- Nenhum segredo ou texto integral do prompt é enviado a logs/auditoria. Nenhuma flag real é habilitada e nenhuma classificação paga é invocada pelos testes.
- Testes de regressão cobrem os critérios acima e `mvn -B -ntp verify`.

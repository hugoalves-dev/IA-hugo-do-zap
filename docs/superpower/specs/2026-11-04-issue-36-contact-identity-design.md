# Issue #36: nomes de contatos e remetentes no contexto da IA

## Intenção

Exibir nomes confiáveis de contatos nas notificações e fornecer ao classificador um contexto estruturado que identifique o autor de cada mensagem, inclusive em grupos, sem expor JIDs/telefones ou confundir pessoas com nomes iguais.

## Evidência e decisão

O código da Evolution API 2.3.7 em `whatsapp.baileys.service.ts` envia `contacts.upsert` com `pushName` derivado de `contact.name || contact.verifiedName || contact.id.split('@')[0]`; `contacts.update` usa `contact.name ?? contact.verifiedName`. A origem não é preservada no payload. O endpoint `findContacts` lê os registros persistidos e devolve `pushName`, sem expor origem. Não existe neste contrato evidência suficiente para marcar esses valores como nome salvo pelo proprietário. Portanto, o watcher continua classificando essa fonte como `NOTIFY` e não chamará `pushName` de nome da agenda.

Ordem efetiva: (1) nome de contato com origem comprovada; (2) nome de perfil disponível na conta do WhatsApp, pessoal ou comercial, recebido como `pushName` e mantido como `NOTIFY`; (3) apelido local opcional definido pelo administrador pelo comando privado `/apelido <jid> <nome...>` e removido por `/apelido <jid> remover`, somente quando não houver nome de contato ou perfil disponível. O apelido tem proveniência `MANUAL`, vinculado ao JID canônico/aliases já verificados, não é sincronização do WhatsApp e nunca sobrepõe o perfil. A issue não autoriza consultar ou alterar a instância Evolution de produção.

## Abordagens consideradas

1. Confiar em `pushName` como nome da agenda. Rejeitada porque a Evolution mistura `name`, `verifiedName` e fallback do JID sem expor proveniência.
2. Buscar contatos em endpoints/API ou em payloads reais da instância. Não adotada sem contrato validado e autorização operacional; uma resposta sem campo de proveniência continuaria ambígua.
3. Documentar o limite; permitir apelido local explícito como substituto do nome salvo e usar o perfil disponível (`NOTIFY`) como fallback. Escolhida porque é segura, reversível e respeita a ordem desejada sem inventar proveniência.

## Objetivos

- Preservar proveniência e prioridade: nome `CONTACT_NAME` comprovado, depois nome de perfil `NOTIFY`, e apelido manual apenas como fallback quando ambos faltarem; as demais fontes conhecidas vêm depois do apelido.
- Resolver nomes e mudanças usando apenas identidades canônicas e aliases PN/LID com evidência explícita; não criar aliases por nome.
- Atualizar próximas notificações, consultas `/ultimas`, `/decisao` e prévias Shadow; em grupos, apresentar o nome do grupo separado da identidade do participante.
- Enviar ao provedor um envelope minimizado por mensagem com papel/origem (`owner`, `contact`, `group_participant`), nome amigável se conhecido, texto não confiável e identificador opaco local para distinguir homônimos quando necessário.
- Usar rótulos neutros sem nome confiável e não enviar JIDs, telefones, lista de contatos ou grupos alheios ao classificador.
- Preservar os limites de contexto e retenção existentes e não reclassificar histórico.

## Fora de escopo e restrições

- Não habilitar classificação, respostas reais, alertas reais ou qualquer flag de produção.
- Não alterar credenciais, `.env`, infraestrutura, configuração Evolution, banco de produção ou deploy.
- Não inferir nomes de agenda de `pushName`, nomes iguais, JIDs, scraping de telefone ou dados de grupos não relacionados.
- Não mudar autorização, templates, vínculo PN/LID ou decisões do classificador com base em nome.
- Usar Flyway para mudanças de esquema e repositórios para SQL; chamadas externas continuam fora de transações.
- Preservar no máximo sete dias de retenção para textos sensíveis.

## Arquitetura proposta

Adicionar campo `manual_display_name` separado do nome base e operações transacionais de definir/remover apelido em `IdentityService`/`IdentityMergeRepository`; manter o valor base intacto permite priorizar sempre o nome de agenda comprovado e o perfil `NOTIFY`, usando o apelido apenas se ambos faltarem. O comando administrativo existente aplica autorização privada e confirmação de mutações. O `IdentityService` continua responsável por precedência e aliases canônicos. Consultas do Telegram reutilizam essa precedência e escapam nomes para HTML.

Enriquecer `ClassificationInput.ContextEntry` com papel, texto e metadados minimizados do remetente. A projeção resolve nomes apenas pelo JID participante existente em cada mensagem. Um token opaco por participante é criado localmente por payload e só identifica remetentes dentro do mesmo contexto. A serialização do provider inclui o envelope tipado para alvo e mensagens anteriores, mantendo texto como dado não confiável. Métricas armazenam apenas contagens, nunca nomes ou IDs.

## Verificação esperada

Testes de contrato usam fixtures sanitizadas da Evolution 2.3.7; cobrem prioridade/renomeação/limpeza manual, mensagens diretas e grupos, participantes homônimos e owner, aliases verificados PN/LID, Unicode e escape HTML, validade/fallback de nome, minimização do payload e limites/expiração do contexto. A suíte completa `mvn -B -ntp verify` e os testes de CI serão executados. Teste com payload real e Evolution em produção não será alegado sem autorização e evidência.

# Issue #9 — prefixo explícito nas respostas do Hugo Zap Bot

Fonte de requisitos: https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/9

## Decisão arquitetural

Compor o prefixo de saída **após** a autorização, uma vez por canal, sem reescrever o texto original dos templates persistidos. O Telegram aplica o prefixo somente aos tipos `telegram_admin_reply` e `telegram_admin_edit` no formatter. O WhatsApp aplica ao body revalidado imediatamente antes de chamar Evolution, removendo o marcador legado `Resposta automática:` do texto enviado.

Alternativas descartadas:
- Salvar o prefixo no corpo de templates e migrar registros: altera conteúdo aprovado, pode afetar autorização e implica migração desnecessária.
- Prefixar toda mensagem Telegram: contaminaria alertas de conexão e notificações de chats.
- Formatar no n8n: acopla regra de identidade do bot ao transporte e não cobre WhatsApp.

## Tarefas e verificação

1. **Testes (RED):** adicionar testes para `TelegramMessageFormatter` e caminho real `AdminCommandService → outbox → formatter`, incluindo erro, confirmação, edição, HTML literal, idempotência e Unicode. Acrescentar testes de despacho WhatsApp fake com template legado e prefixo já presente, e template sem conteúdo. Ampliar teste de upgrade V16→V17 para preservar template divulgado e habilitado.
2. **Implementação (GREEN):** criar utilitário de prefixo `BotReplyFormatter`; aplicar no ramo administrativo do `TelegramMessageFormatter`. Converter template de WhatsApp somente em `WhatsAppActionService.revalidate`, exigindo disclosure antes de enfileirar e outra vez antes de entregar. Manter `WhatsAppActionDispatcher` e tratamento de `UNKNOWN` intactos.
3. **Documentação:** explicar o contrato de formato dos dois canais, a necessidade do disclosure de banco para templates antigos e a ausência de migração ou mudança de flags.
4. **Verificação:** rodar `mvn -B -ntp verify` no CI PR com Java 25, revisar diff, manter branch separada e não fazer merge nem deploy.

## Restrições invariantes

- Não alterar `V17__required_automation_disclosure_and_real_cooldown_contract.sql` nem desabilitar/ativar templates.
- Não mudar nenhuma flag de envio real nem credenciais.
- Não alterar o texto armazenado em `response_templates`; manter V17 compatível.
- Não enviar mensagens WhatsApp reais durante os testes; transport fake.
- Notificações tradicionais Telegram e relatórios de simulação não são respostas a comandos, logo preservam seus títulos.

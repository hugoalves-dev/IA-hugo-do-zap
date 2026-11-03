# Issue #34: formatação HTML no Telegram

**Escopo:** somente UX no Telegram. Nenhuma alteração em IA, autorização, flags de envio de WhatsApp ou pipeline de deploy. Issue: https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/34

## Abordagens
- **Escolhida:** manter `TelegramMessageFormatter` como responsável por montar HTML seguro; rotear eventos HTML aos métodos já disponíveis da Bot API, adicionando variantes para menus administrativos com teclado quando necessário.
- Descartada: interpretar HTML em todos os envios, inclusive respostas administrativas livres. Isso expõe conteúdo não escapado e quebra texto literal.
- Descartada: MarkdownV2. Exige escaping específico e muda a semântica dos links existentes.

## Tarefas

1. **Formatter**: testes de título `<b>`, citação `<blockquote>` com linhas, vários chats, nome/URL validados, sanitização de `& < > " '`, limites de tamanho sem cortar tags e emoji; resumo diário e alerta com título em negrito; suporte de formatação para futura notificação `simulation_reply_preview`, sem criar produtor.
2. **Transporte/dispatcher**: testes de `ParseMode.HTML` para envio e edição, preservando teclado inline e mantendo respostas administrativas comuns em texto puro. Somente o menu administrativo estruturado usa HTML; alerta/digest/notificação usam `sendHtml`.
3. **Integração**: testes de `NotificationService` (ACK/retry e roteamento) e `mvn verify` via GitHub Actions no PR, pois o ambiente externo não possui Maven/Java 25. Falhas de renderização nunca devem propagar conteúdo sem escape nem quebrar a outbox.
4. **Entrega**: PR contra `main`; proibir merge/deploy nesta issue. A CI de PR roda testes, mas não executa `publish-image` nem `deploy-vps`.

## Critérios de aceite
- HTML construído exclusivamente de constantes e strings dinâmicas escapadas.
- Limites conservadores: 3500 caracteres na string HTML final (abaixo dos 4096 do Telegram após parsing), com tags sempre balanceadas.
- `@lid` e grupos sem link `wa.me` artificial.
- Prefixo administrativo exibido exatamente uma vez, inclusive edição com teclado.
- Eventuais produtores da #31 poderão emitir `simulation_reply_preview` posteriormente; esta issue não ativa respostas/simulações.

# Tarefa 3 — Administração Telegram privada

## Implementação

- Polling pengrad por `getUpdates`, sem webhook, desligado por padrão. A configuração requer `watcher.telegram.admin.polling-enabled=true` e `watcher.telegram.admin.user-id`; o chat autorizado é o `watcher.telegram.chat-id` existente.
- Exclusão entre instâncias com advisory lock PostgreSQL. Cada update, mutação, auditoria, resposta na outbox e avanço do offset compartilham transação. Falha antes do commit permite retry sem duplicar mutação.
- Handler valida usuário, ID e tipo privado do chat para mensagens e callbacks. Tokens de confirmação são aleatórios, armazenados como SHA-256, vinculados a usuário/chat, expiram em até cinco minutos e são consumidos atomicamente uma vez.
- Comandos: `/status`, `/ocupado`, `/disponivel`, `/ferias`, `/simulacao`, `/relatorio`, `/emergencia`; menus de perfil/template/feriados/orçamento mostram comandos de controle. Mudanças de estado exigem confirmação, exceto `/emergencia`, que ativa imediatamente o kill switch.
- Respostas administrativas seguem pela outbox existente. A auditoria registra ação e identidade administrativa, sem token, conteúdo de conversa ou segredo. Relatório contém contagens e custo reservado, sem conteúdo bruto.
- `/emergencia` desliga classificação, alertas e respostas reais; não invoca serviço de IA. Nenhum comando ativa ações reais. O polling e ações reais permanecem desligados por padrão.
- Migration nova: V22. O toggle de simulação é persistido e consultado pelo scheduler.
- Não houve chamadas Telegram reais, uso de IA, conexão com VPS ou alterações de configuração operacional.

## Verificação isolada

Scratch: `H:\Script\IA-hugo-do-zap-evolution-poc-task3-scratch`, criado copiando somente `src/` e `pom.xml`. PostgreSQL 17 via Testcontainers; updates pengrad e serviços externos foram fakeados.

- RED observado: `mvn "-Dtest=TelegramUpdateHandlerTest,CallbackTokenServiceTest,AdminCommandServiceTest" test` falhou antes da implementação, pois as interfaces ainda não existiam.
- RED→GREEN: teste do scheduler falhou sem o toggle runtime, depois passou com ele.
- RED→GREEN: teste de replay/crash encontrou auditoria commitada com offset antigo; passou após envolver ação e offset na mesma transação.
- RED→GREEN: teste de retenção encontrou payload de token expirado ainda armazenado; tokens expirados/usados são removidos na emissão seguinte.
- Obrigatório: `mvn "-Dtest=TelegramUpdateHandlerTest,CallbackTokenServiceTest,AdminCommandServiceTest" test` — 16 testes, 0 falhas/erros/skips.
- Expandido: `mvn "-Dtest=TelegramUpdateHandlerTest,CallbackTokenServiceTest,AdminCommandServiceTest,TelegramUpdatePollingServiceTest,SimulationSchedulerTest" test` — 22 testes, 0 falhas/erros/skips.
- Suite completa não executada conforme instrução do despacho.

## Limites

A integração live não foi validada. O Bot API só será consultado quando o polling for habilitado em configuração; respostas utilizam o dispatcher outbox existente. Menus usam comandos privados de texto; confirmação pode ocorrer por `/confirm <token>` ou callback válido.
# Aceitação local e autorização de ativação

Esta entrega valida comportamento local com PostgreSQL e transportes fake. Não valida contas, faturamento, privacidade contratual, sessão WhatsApp, dispositivo pareado, VPS ou entrega remota. Screenshots não substituem evidência de configuração, contratos, eventos correlacionados e resultados persistidos.

## Estado padrão

| Controle | Estado padrão |
| --- | --- |
| `watcher.ai.enabled` | `false` |
| `automation_settings.ai_classification_enabled` | `false` |
| `automation_settings.kill_switch_enabled` | `true` |
| `automation_settings.intelligent_alerts_enabled` | `false` |
| `automation_settings.auto_reply_enabled` | `false` |
| `watcher.telegram.intelligent-alerts-enabled` | `false` |
| `watcher.whatsapp-actions.real-send-enabled` | `false` |
| `watcher.decision-producer.real-mode-enabled` | `false` |
| Transporte Evolution de ações | Implementação desativada; nenhuma integração REST real instalada |
| Limites diário/mensal e consentimentos de dados | Nenhum registro padrão |

Com `watcher.decision-producer.real-mode-enabled=false`, simulação não chama serviços de enqueue de ações/alertas. Decisões `would_alert`/`would_reply` permanecem previsão. O modo é capturado em `simulation_decisions.production_mode`; decisões antigas ou shadow não são promovidas por replay após ligar a flag. Em modo real explicitamente autorizado, persistência da decisão e enqueue dos dois canais compartilham a transação. Falha de persistência/enqueue reverte decisão, tombstones e filas; resultado durável do modelo permite retomar sem nova chamada paga. Cada canal ainda exige sua própria flag e autorização atual. Relatório diário agregado pode ser explicitamente enfileirado para Telegram; nunca inclui trechos, JIDs ou evidências do modelo. Transporte legado de notificações arquivadas permanece separado.

## Checklist local, sem credenciais reais

- [ ] Executar testes dirigidos em scratch, JDK 25, PostgreSQL 17 Testcontainers. Usar exclusivamente transportes fake Gemini, OpenRouter, Telegram e Evolution.
- [ ] Confirmar schema V29, checksums anteriores preservados, zero consentimentos e zero limites inseridos pela migração. Não usar `flyway repair`.
- [ ] Confirmar que falta de uma credencial, timeout válido, hold positivo, consentimento, limite diário ou mensal impede qualquer HTTP pago.
- [ ] Confirmar `DISPATCHED` persistido antes de cada HTTP: primário, retry de schema e fallback. Conferir contadores de chamada e registros SQL, não screenshots.
- [ ] Confirmar concorrência em orçamento e batch, rollback da decisão, timeout, claim expirado e recuperação sem redispatch automático.
- [ ] Revogar consentimento e acionar kill switch entre tentativas; confirmar que nenhum novo HTTP sai. Revalidar consentimento também antes de persistir decisão e ao reutilizar resultado.
- [ ] Confirmar `ARCHIVED_ONLY`/`UNARCHIVED_ONLY` bloqueiam snapshot ausente ou não sincronizado. `ALL` aceita estado desconhecido por definição.
- [ ] Conferir decisão positiva com autorização/template explícitos, nenhuma ação WhatsApp e nenhum alerta individual em Shadow Mode.
- [ ] Conferir relatório Telegram fake agregado e ausência de texto/JID. Conferir Evolution fake com zero chamadas e dispatcher real desligado.
- [ ] Conferir ausência de chaves, snippets, prompts e evidências do modelo em logs, ledger de custo e resultado persistido.

## Antes de qualquer provedor pago

Requer autorização operacional explícita do usuário. Este checklist não configura contas nem habilita chamadas.

- [ ] Registrar consentimento de privacidade por conversa, situação e provedor em `ai_data_consents`, com `privacy_approved=true` e expiração futura. Consentimento de dados não concede permissão de resposta, alerta ou template. OpenRouter precisa consentimento separado para todos os propósitos da tentativa primária.
- [ ] Revisar retenção/treinamento e elegibilidade contratual do modelo Gemini escolhido. Confirmar rota OpenRouter com `zdr=true`, `data_collection=deny`, `allow_fallbacks=false` e parâmetros obrigatórios. Ausência de rota privada bloqueia; nunca relaxar parâmetros para conseguir resposta.
- [ ] Definir `watcher.ai.gemini-api-key`, `gemini-model`, `attempt-hold-micros` e `timeout`. Timeout deve ser positivo e no máximo 30 segundos. Fallback exige `open-router-api-key` e `open-router-model`. Manter segredos fora de Git e relatórios.
- [ ] Definir ambos os limites `ai_budget_limits` em USD micros, períodos UTC, e hold conservador que cubra o máximo por tentativa considerando entrada limitada e saída de 300 tokens. Cada retry/fallback consome nova reserva.
- [ ] Definir quotas e limites na própria conta do provedor, sem recarga automática. O ledger local limita reservas, não garante teto rígido de faturamento externo. Custo acima da estimativa é registrado e bloqueia próximas chamadas.
- [ ] Confirmar política de reconciliação: custo monetário explícito válido de OpenRouter é liquidado arredondando para cima em micros. Token usage Gemini não prova valor pago: permanece `UNCERTAIN`, inclusive em sucesso. Timeout, erro e custo ausente também mantêm hold integral. Nunca liberar `DISPATCHED`/`UNCERTAIN` por expiração ou suposição de zero.
- [ ] Prever efeito operacional: sem reconciliação de Gemini, holds vão consumir orçamento disponível. Liquidar somente mediante evidência de faturamento, inclusive zero. Claim interrompido/expirado nunca é enviado novamente automaticamente; batch pode terminar com classificação indisponível.
- [ ] Confirmar retenção: snippets até sete dias; metadados de classificação sem texto/evidência seguem exclusão do batch via FK; ledger financeiro é separado e não é purgado automaticamente. Expiração de consentimento bloqueia uso; remoção de configuração expirada e retenção de backups exigem rotina operacional própria. Evolution, Telegram e provedores têm retenções independentes.
- [ ] Para referência pessoal/grupo, configurar `watcher.ai.own-jid` com identidade verificada. Configurar também `watcher.whatsapp-actions.owner-jid` com a mesma identidade verificada para produtor e dispatch. São aceitos `contextInfo.mentionedJid` exato ou `contextInfo.stanzaId` que referencia mensagem própria autenticada e indexada em `whatsapp_owner_message_references`, nos formatos extended text, imagem, vídeo e documento. A fonte vem exclusivamente de mensagens persistidas ainda válidas, recebidas e sem histórico. Texto/modelo e payload fornecido em memória não concedem referência. Auditoria guarda urgência/confiança/status normalizados e SHA-256 do ID da fonte verificável; nunca guarda evidência textual do modelo.

## Antes de parear, alterar VPS ou enviar mensagens reais

Cada operação requer autorização operacional explícita. Nenhuma foi executada nesta tarefa.

- [ ] Comprovar paridade Evolution com eventos e respostas REST sanitizados: autenticação, instância correta, identidade, histórico versus mensagem nova, `fromMe`, aliases, participantes, timestamps e snapshot sincronizado de arquivamento. Correlacionar entrada, normalização e estado PostgreSQL. Screenshots não bastam.
- [ ] Confirmar ausência de API de alterar arquivamento e coexistência/rollback do transporte legado, sem dois consumidores do mesmo evento.
- [ ] Confirmar usuário/chat Telegram privados permitidos, polling único e ausência de webhook concorrente no mesmo bot. Confirmar callbacks expirados/repetidos bloqueados. Telegram é at-least-once; resultado remoto ambíguo pode duplicar mensagem.
- [ ] Para produção explicitamente autorizada, habilitar `watcher.decision-producer.real-mode-enabled` e apenas os canais desejados (`watcher.telegram.intelligent-alerts-enabled` e/ou `watcher.whatsapp-actions.real-send-enabled`), além dos gates SQL. Produtor aceita somente decisão auditada em modo real, classificação válida/confiante e consentimento atual. Cada canal revalida privacidade por provedor/propósito, scope, expiração, disponibilidade, referência objetiva quando exigida, cooldown, situação, template/versionamento e kill switch antes da entrega. Referência crítica pode alertar durante indisponibilidade; resposta automática mantém a política existente de template para retorno posterior. Versão nova de template não substitui autorização auditada pendente. Cooldown real é compartilhado por conversa e começa apenas na confirmação de Telegram ou WhatsApp. `last_real_decision_id` identifica a decisão causadora: canais irmãos dessa decisão continuam elegíveis, com todos os demais gates ativos, em qualquer ordem de entrega. Confirmação irmã posterior estende a janela; decisões futuras permanecem bloqueadas. ACK de Telegram e confirmação WhatsApp atualizam estado/cooldown na mesma transação, com lease/status idempotentes e timestamp monotônico. Simulação, supressão, falha e resultado ambíguo não avançam cooldown. Despacho reserva de forma durável o hash da conversa antes do HTTP, sem transação aberta durante rede. Outra decisão é adiada enquanto há tentativa `DISPATCHED`/`UNKNOWN`; canais irmãos da mesma decisão podem avançar. Lease vencido de alerta já despachado não transfere o token do worker original. Crash depois da marca de despacho, timeout, resposta ausente ou 5xx não concedem reenvio automático. Rejeição explícita pode gerar nova tentativa; confirmação, retry conhecido e estado remoto ambíguo ficam separados. Tombstones de ambiguidade não expiram para liberar conversa: exigem reconciliação por prova remota independente, ainda não implementada; preservá-los ao purgar payloads ou reiniciar workers.
- [ ] Implementar e revisar transporte Evolution real antes de habilitar flag: atualmente desativado. Exigir opt-in por conversa/situação, template aprovado com identificação de automação, cooldown, limites e revalidação antes do envio.
- [ ] Planejar rollback: ativar kill switch; desligar `watcher.ai.enabled`, flag SQL de IA, flag do produtor, flags de alerta e real-send; revogar consentimentos quando necessário; preservar holds, claims e ações `UNKNOWN` para reconciliação. Não apagar reserva ambígua para restaurar capacidade.
- [ ] Observar limite da revogação: gates são reavaliados imediatamente antes do HTTP, sem transação/lock mantidos durante rede. Uma revogação concorrente após o último gate não recolhe requisição já despachada. Verificar efeito por ledger e evidência remota.

Ativação paga, pareamento, VPS e envios reais continuam pendentes de autorização e provas próprias. Testes fake não satisfazem essas etapas.

# Ativação de IA em Shadow Mode

Este roteiro não ativa produção nem autoriza gastos. Preserve `archive_monitor_settings.mode=ALL` se já escolhido; a migração não altera o escopo. Em `ALL`, texto minimizado de qualquer conversa elegível pode ser enviado ao provedor quando o consentimento por chat é dispensado. Isso não substitui consentimento dos participantes: restrinja o escopo e minimize dados conforme necessário.

1. Configure limites na conta OpenRouter e revise sua política de privacidade e rota ZDR. A quota externa pode ter atrasos ou regras diferentes do ledger local. Ela não cobre Gemini.
2. Edite o `.env` privado com `vim .env`. Somente após autorização consciente, configure chave e modelo OpenRouter e os valores abaixo:

```dotenv
WATCHER_AI_ENABLED=true
WATCHER_AI_CLASSIFICATION_ENABLED=true
WATCHER_AI_DISABLE_LOCAL_BUDGET=true
WATCHER_AI_REQUIRE_CHAT_CONSENT=false
WATCHER_AUTOMATION_KILL_SWITCH_ENABLED=false
WATCHER_AUTOMATION_INTELLIGENT_ALERTS_ENABLED=false
WATCHER_AUTOMATION_AUTO_REPLY_ENABLED=false
WATCHER_SIMULATION_ENABLED=true
WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED=false
WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED=false
WATCHER_AI_ALLOW_UNBUDGETED_GEMINI_FALLBACK=false
```

3. Recrie apenas o watcher: `docker compose --env-file .env up -d --no-deps --force-recreate watcher`. Nenhum `INSERT` ou `UPDATE` de configuração é necessário. As colunas antigas de `automation_settings` e `telegram_admin_settings.simulation_enabled` permanecem no banco, mas não habilitam nem desabilitam essas opções.
4. Envie uma nova mensagem textual elegível. Consulte `simulation_ai_evaluations` e `simulation_decisions` somente para leitura; verifique `classification_status`, `production_mode=false` e ausência de ações WhatsApp ou alertas individuais. Mensagens sem texto não iniciam HTTP pago; decisões antigas não são reclassificadas automaticamente.
5. Desligue IA no `.env` e recrie o watcher. Para corte imediato, use `/emergencia` no Telegram autorizado. Emergências anteriores registradas na auditoria também são preservadas no upgrade. A trava persiste após restart e alterações do ambiente; `/status` a mostra. Para recuperá-la, use `/recuperar` e confirme o token no mesmo chat autorizado. O ambiente continua impondo seus bloqueios. Uma chamada já enviada não pode ser recolhida; seu resultado é revalidado antes de uso.

Os defaults preservam orçamento local (`WATCHER_AI_DISABLE_LOCAL_BUDGET=false`) e consentimento (`WATCHER_AI_REQUIRE_CHAT_CONSENT=true`). Nesse modo continuam exigidos limites diário e mensal, hold positivo e consentimento válido por conversa, situação e provedor; reservas, ledger e reconciliação permanecem disponíveis. Com bypass do orçamento, não há reserva nem ledger para essas tentativas. Fallback Gemini com bypass exige também opt-in `WATCHER_AI_ALLOW_UNBUDGETED_GEMINI_FALLBACK=true` e quota própria. Não grave chaves nem conteúdo de conversas em logs ou no repositório.

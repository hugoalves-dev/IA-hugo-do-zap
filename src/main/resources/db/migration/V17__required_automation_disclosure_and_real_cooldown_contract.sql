UPDATE response_templates
   SET enabled = false
 WHERE identifies_automation
   AND lower(ltrim(body)) NOT LIKE 'resposta automática:%';

ALTER TABLE response_templates
    ADD CONSTRAINT ck_response_templates_automation_disclosure
    CHECK (NOT enabled OR (identifies_automation AND lower(ltrim(body)) LIKE 'resposta automática:%'));

COMMENT ON COLUMN decision_cooldowns.last_real_action_at IS
    'Plano 3 deve atualizar este campo atomicamente com o resultado de entrega real confirmada; nunca em simulação, tentativa sem confirmação ou falha. Plano 3 deve testar o cooldown após sucesso e ausência de atualização após falha.';

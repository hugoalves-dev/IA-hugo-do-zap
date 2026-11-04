-- Group owner messages are context only when they explicitly reply to a
-- retained inbound message. The participant is resolved from that message.
ALTER TABLE simulation_owner_context
    ADD COLUMN reply_to_external_id VARCHAR(255);

CREATE INDEX simulation_owner_context_reply_idx
    ON simulation_owner_context (chat_jid, reply_to_external_id, received_at DESC)
    WHERE reply_to_external_id IS NOT NULL;

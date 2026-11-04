ALTER TABLE telegram_prompt_input_sessions
    ADD COLUMN draft_id UUID REFERENCES classification_prompt_drafts(id);

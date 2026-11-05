ALTER TABLE prompt_generation_jobs
    DROP CONSTRAINT ck_prompt_generation_result_delivery_status,
    ADD CONSTRAINT ck_prompt_generation_result_delivery_status
        CHECK (result_delivery_status IN
            ('NOT_ENQUEUED','PENDING','PROCESSING','ACKNOWLEDGED','SUPPRESSED','CANCELLED','DEAD_LETTER','EXPIRED'));

ALTER TABLE holiday_calendar
    ADD COLUMN validated_by TEXT NULL,
    ADD COLUMN validated_at TIMESTAMPTZ NULL;

UPDATE holiday_calendar
   SET validation_status = 'UNKNOWN'
 WHERE validation_status = 'VALIDATED';

ALTER TABLE holiday_calendar
    ADD CONSTRAINT ck_holiday_calendar_manual_attestation
    CHECK (validation_status <> 'VALIDATED'
        OR (source IS NOT NULL AND length(trim(source)) > 0
            AND calendar_year = EXTRACT(YEAR FROM local_date)::INTEGER
            AND validated_by IS NOT NULL AND length(trim(validated_by)) > 0
            AND validated_at IS NOT NULL));

COMMENT ON COLUMN holiday_calendar.source IS
    'Reference to the publication checked by a maintainer; the system does not fetch or verify it.';
COMMENT ON COLUMN holiday_calendar.validation_status IS
    'VALIDATED means a maintainer manually attested the entry against its source; it is not an automated factual check.';
COMMENT ON COLUMN holiday_calendar.validated_by IS
    'Identity of the maintainer who manually checked and marked this calendar entry VALIDATED.';
COMMENT ON COLUMN holiday_calendar.validated_at IS
    'Time when the maintainer recorded the manual validation attestation.';

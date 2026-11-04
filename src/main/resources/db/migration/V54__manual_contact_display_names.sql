ALTER TABLE contact_identity
    ADD COLUMN manual_display_name TEXT NULL,
    ADD COLUMN manual_display_name_updated_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN contact_identity.manual_display_name IS
    'Owner-maintained local alias; not synchronized from the WhatsApp address book.';

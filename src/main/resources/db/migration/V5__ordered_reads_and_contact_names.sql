ALTER TABLE chat_snapshot ADD COLUMN last_read_at TIMESTAMPTZ NULL;

CREATE TABLE contact_identity (
    canonical_jid VARCHAR(255) PRIMARY KEY,
    lid_jid VARCHAR(255) NULL UNIQUE,
    phone_jid VARCHAR(255) NULL UNIQUE,
    username VARCHAR(64) NULL,
    display_name TEXT NULL,
    display_name_source VARCHAR(32) NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_contact_identity_name_source CHECK (display_name_source IS NULL OR display_name_source IN
        ('CONTACT_NAME', 'NOTIFY', 'VERIFIED', 'CHAT', 'LEGACY', 'USERNAME', 'PHONE'))
);
CREATE INDEX ix_contact_identity_username ON contact_identity (username);

INSERT INTO contact_identity(canonical_jid, display_name, display_name_source, updated_at)
SELECT DISTINCT ON (canonical_jid) canonical_jid, display_name, 'LEGACY', snapshot_at
FROM (
    SELECT COALESCE(identity.canonical_identity, snapshot.chat_jid) AS canonical_jid,
           snapshot.display_name, snapshot.snapshot_at
    FROM chat_snapshot snapshot
    LEFT JOIN identity_aliases identity ON identity.alias = snapshot.chat_jid
    WHERE snapshot.display_name IS NOT NULL
) named_snapshots
ORDER BY canonical_jid, snapshot_at DESC
ON CONFLICT (canonical_jid) DO NOTHING;

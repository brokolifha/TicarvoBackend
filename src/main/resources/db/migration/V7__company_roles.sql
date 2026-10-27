ALTER TABLE company_memberships
    ADD CONSTRAINT ck_membership_role CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    -- MySQL has no partial unique index: this column is set only on the OWNER row, so a company can have exactly one owner.
    ADD COLUMN owner_company_id CHAR(36) GENERATED ALWAYS AS (CASE WHEN role = 'OWNER' THEN company_id END) STORED,
    ADD CONSTRAINT uq_membership_single_owner UNIQUE (owner_company_id);
CREATE TABLE company_member_invitations (
    id CHAR(36) NOT NULL PRIMARY KEY,
    company_id CHAR(36) NOT NULL,
    email VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    role VARCHAR(30) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invited_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_member_invitation_token UNIQUE (token_hash),
    CONSTRAINT uq_member_invitation_email UNIQUE (company_id, email),
    CONSTRAINT ck_member_invitation_role CHECK (role IN ('ADMIN','MEMBER')),
    CONSTRAINT fk_member_invitation_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_member_invitation_inviter FOREIGN KEY (invited_by_user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

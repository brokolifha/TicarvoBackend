-- ADR-013 (DSA Art. 16): anyone can report illegal content without an account, confirmed by e-mail.
ALTER TABLE complaints
    MODIFY reporter_user_id CHAR(36) NULL,
    ADD COLUMN reporter_name VARCHAR(100) NULL,
    ADD COLUMN reporter_email VARCHAR(254) NULL,
    ADD CONSTRAINT ck_complaint_reporter CHECK (reporter_user_id IS NOT NULL OR reporter_email IS NOT NULL);

-- A notice waits here until its sender confirms the mailed code; unconfirmed ones expire and are removed.
CREATE TABLE pending_notices (
    id CHAR(36) NOT NULL PRIMARY KEY,
    reporter_name VARCHAR(100) NOT NULL,
    reporter_email VARCHAR(254) NOT NULL,
    company_id CHAR(36) NOT NULL,
    product_id CHAR(36) NULL,
    reason VARCHAR(40) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_pending_notice_token UNIQUE (token_hash),
    INDEX ix_pending_notice_email (reporter_email, created_at),
    INDEX ix_pending_notice_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

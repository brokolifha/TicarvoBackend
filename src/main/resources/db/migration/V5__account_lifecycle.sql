ALTER TABLE app_users ADD COLUMN email_verified_at DATETIME(6) NULL;
-- Accounts created before M4.1 are treated as verified so nobody is locked out.
UPDATE app_users SET email_verified_at = created_at;
CREATE TABLE account_tokens (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_account_token_hash UNIQUE(token_hash),
    CONSTRAINT ck_account_token_purpose CHECK(purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET')),
    CONSTRAINT fk_account_token_user FOREIGN KEY(user_id) REFERENCES app_users(id),
    INDEX ix_account_token_user(user_id, purpose, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

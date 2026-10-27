ALTER TABLE app_users
    ADD COLUMN mfa_secret VARCHAR(100) NULL,
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_last_step BIGINT NULL;
ALTER TABLE account_tokens DROP CHECK ck_account_token_purpose;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_token_purpose
    CHECK(purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET','MFA_RECOVERY'));

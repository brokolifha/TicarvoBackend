-- C-03: an email change is confirmed with a single-use code mailed to the new address, which the token remembers.
ALTER TABLE account_tokens ADD COLUMN new_email VARCHAR(254) NULL;
ALTER TABLE account_tokens DROP CHECK ck_account_token_purpose;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_token_purpose
    CHECK(purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET','MFA_RECOVERY','EMAIL_CHANGE'));
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_token_new_email
    CHECK((purpose = 'EMAIL_CHANGE') = (new_email IS NOT NULL));

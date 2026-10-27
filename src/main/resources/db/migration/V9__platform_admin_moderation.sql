-- ADR-008: platform admins, complaints, suspensions with statements of reasons, appeals and an append-only admin audit log.
ALTER TABLE app_users ADD COLUMN suspended_at DATETIME(6) NULL;
ALTER TABLE companies ADD COLUMN suspended_at DATETIME(6) NULL;

-- Changed only by the command-line grant/revoke (ADR-008), never over HTTP.
CREATE TABLE platform_admins (
    user_id CHAR(36) NOT NULL PRIMARY KEY,
    granted_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_platform_admin_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- actor_user_id is NULL for command-line actions.
CREATE TABLE admin_audit_log (
    id CHAR(36) NOT NULL PRIMARY KEY,
    actor_user_id CHAR(36) NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_admin_audit_target (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE complaints (
    id CHAR(36) NOT NULL PRIMARY KEY,
    reporter_user_id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    product_id CHAR(36) NULL,
    reason VARCHAR(40) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    admin_note VARCHAR(2000) NULL,
    reviewer_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_complaint_reporter FOREIGN KEY (reporter_user_id) REFERENCES app_users(id),
    CONSTRAINT fk_complaint_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT ck_complaint_status CHECK (status IN ('OPEN','IN_REVIEW','ACTION_TAKEN','DISMISSED')),
    INDEX ix_complaint_reporter (reporter_user_id, created_at),
    INDEX ix_complaint_queue (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only decision log; the latest row per target is its current state.
CREATE TABLE moderation_restrictions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id CHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    reason VARCHAR(40) NULL,
    statement VARCHAR(2000) NOT NULL,
    complaint_id CHAR(36) NULL,
    admin_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    appeal_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    appeal_until DATETIME(6) NULL,
    CONSTRAINT uq_restriction_appeal_token UNIQUE (appeal_token_hash),
    CONSTRAINT ck_restriction_target CHECK (target_type IN ('USER','COMPANY')),
    CONSTRAINT ck_restriction_action CHECK (action IN ('SUSPEND','RESTORE')),
    CONSTRAINT fk_restriction_complaint FOREIGN KEY (complaint_id) REFERENCES complaints(id),
    CONSTRAINT fk_restriction_admin FOREIGN KEY (admin_user_id) REFERENCES app_users(id),
    INDEX ix_restriction_target (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE moderation_appeals (
    id CHAR(36) NOT NULL PRIMARY KEY,
    restriction_id CHAR(36) NOT NULL,
    text VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decision_note VARCHAR(2000) NULL,
    decided_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_appeal_restriction UNIQUE (restriction_id),
    CONSTRAINT ck_appeal_status CHECK (status IN ('PENDING','UPHELD','REVERSED')),
    CONSTRAINT fk_appeal_restriction FOREIGN KEY (restriction_id) REFERENCES moderation_restrictions(id),
    INDEX ix_appeal_queue (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

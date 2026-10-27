ALTER TABLE companies ADD COLUMN public_seller BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_company_public_name ON companies(public_seller, name, id);

CREATE TABLE trade_relationships (
    id CHAR(36) NOT NULL PRIMARY KEY,
    buyer_company_id CHAR(36) NOT NULL,
    seller_company_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    requested_by_user_id CHAR(36) NOT NULL,
    last_action_by_user_id CHAR(36) NOT NULL,
    CONSTRAINT uq_trade_pair UNIQUE(buyer_company_id, seller_company_id),
    CONSTRAINT ck_trade_distinct CHECK(buyer_company_id <> seller_company_id),
    CONSTRAINT ck_trade_status CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED')),
    CONSTRAINT fk_trade_buyer FOREIGN KEY(buyer_company_id) REFERENCES companies(id),
    CONSTRAINT fk_trade_seller FOREIGN KEY(seller_company_id) REFERENCES companies(id),
    CONSTRAINT fk_trade_requester FOREIGN KEY(requested_by_user_id) REFERENCES app_users(id),
    CONSTRAINT fk_trade_actor FOREIGN KEY(last_action_by_user_id) REFERENCES app_users(id),
    INDEX ix_trade_buyer_list(buyer_company_id, status, created_at, id),
    INDEX ix_trade_seller_list(seller_company_id, status, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE trade_relationship_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    relationship_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    actor_company_id CHAR(36) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    relationship_version BIGINT NOT NULL,
    CONSTRAINT uq_trade_event_version UNIQUE(relationship_id, relationship_version),
    CONSTRAINT fk_event_relationship FOREIGN KEY(relationship_id) REFERENCES trade_relationships(id),
    CONSTRAINT fk_event_user FOREIGN KEY(actor_user_id) REFERENCES app_users(id),
    CONSTRAINT fk_event_company FOREIGN KEY(actor_company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seller_invitations (
    seller_company_id CHAR(36) NOT NULL PRIMARY KEY,
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    created_by_user_id CHAR(36) NOT NULL,
    CONSTRAINT uq_invitation_hash UNIQUE(code_hash),
    CONSTRAINT fk_invitation_seller FOREIGN KEY(seller_company_id) REFERENCES companies(id),
    CONSTRAINT fk_invitation_creator FOREIGN KEY(created_by_user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

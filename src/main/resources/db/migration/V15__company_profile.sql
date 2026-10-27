-- ADR-011: company billing and showcase profile, admin-verified seller status and the selling permission.
ALTER TABLE companies
    ADD COLUMN legal_name VARCHAR(200) NULL,
    ADD COLUMN tax_id VARCHAR(11) NULL,
    ADD COLUMN tax_office VARCHAR(100) NULL,
    ADD COLUMN address VARCHAR(500) NULL,
    ADD COLUMN city VARCHAR(60) NULL,
    ADD COLUMN phone VARCHAR(20) NULL,
    ADD COLUMN description VARCHAR(2000) NULL,
    ADD COLUMN website VARCHAR(200) NULL,
    ADD COLUMN logo_key VARCHAR(120) NULL,
    ADD COLUMN seller_status VARCHAR(10) NOT NULL DEFAULT 'NONE',
    ADD COLUMN seller_note VARCHAR(500) NULL,
    ADD COLUMN seller_applied_at DATETIME(6) NULL,
    -- Companies that existed before this change could already sell; new ones get their value from the application.
    ADD COLUMN selling_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT ck_company_seller_status CHECK (seller_status IN ('NONE','PENDING','VERIFIED','REJECTED')),
    ADD CONSTRAINT ck_company_tax_id CHECK (tax_id IS NULL OR tax_id REGEXP '^[0-9]{10,11}$');
CREATE INDEX ix_companies_seller_queue ON companies (seller_status, seller_applied_at);

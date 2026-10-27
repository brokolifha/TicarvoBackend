CREATE TABLE catalog_products (
 id CHAR(36) NOT NULL PRIMARY KEY,
 seller_company_id CHAR(36) NOT NULL,
 sku VARCHAR(64) NOT NULL,
 name VARCHAR(200) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 status VARCHAR(20) NOT NULL,
 public_visible BOOLEAN NOT NULL DEFAULT FALSE,
 b2b_amount DECIMAL(19,2) NOT NULL,
 currency CHAR(3) NOT NULL,
 tax_included BOOLEAN NOT NULL,
 version BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_catalog_sku UNIQUE(seller_company_id,sku),
 CONSTRAINT uq_catalog_product_seller UNIQUE(id,seller_company_id),
 CONSTRAINT fk_catalog_seller FOREIGN KEY(seller_company_id) REFERENCES companies(id),
 CONSTRAINT ck_catalog_status CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 CONSTRAINT ck_catalog_amount CHECK(b2b_amount>=0),
 CONSTRAINT ck_catalog_currency CHECK(currency IN ('TRY','USD','EUR')),
 INDEX ix_catalog_owner(seller_company_id,status,created_at,id),
 INDEX ix_catalog_public(public_visible,status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE catalog_product_buyers (
 product_id CHAR(36) NOT NULL,
 seller_company_id CHAR(36) NOT NULL,
 buyer_company_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(product_id,buyer_company_id),
 CONSTRAINT fk_grant_product_seller FOREIGN KEY(product_id,seller_company_id) REFERENCES catalog_products(id,seller_company_id),
 CONSTRAINT fk_grant_seller FOREIGN KEY(seller_company_id) REFERENCES companies(id),
 CONSTRAINT fk_grant_buyer FOREIGN KEY(buyer_company_id) REFERENCES companies(id),
 CONSTRAINT ck_grant_distinct CHECK(buyer_company_id<>seller_company_id),
 INDEX ix_catalog_buyer(buyer_company_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE catalog_product_events (
 id CHAR(36) NOT NULL PRIMARY KEY,
 product_id CHAR(36) NOT NULL,
 actor_user_id CHAR(36) NOT NULL,
 action VARCHAR(32) NOT NULL,
 product_version BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_catalog_event_version UNIQUE(product_id,product_version),
 CONSTRAINT fk_catalog_event_product FOREIGN KEY(product_id) REFERENCES catalog_products(id),
 CONSTRAINT fk_catalog_event_actor FOREIGN KEY(actor_user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ADR-009: seller-confirmed B2B orders, one order per seller per checkout, optional stock, offline payment tracking.
ALTER TABLE catalog_products
    ADD COLUMN stock_quantity INT NULL,
    ADD CONSTRAINT ck_product_stock CHECK (stock_quantity IS NULL OR stock_quantity >= 0);

-- One row per buyer request; the unique key makes a retried request return the first result.
CREATE TABLE order_checkouts (
    id CHAR(36) NOT NULL PRIMARY KEY,
    buyer_company_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_checkout_key UNIQUE (buyer_company_id, idempotency_key),
    CONSTRAINT fk_checkout_buyer FOREIGN KEY (buyer_company_id) REFERENCES companies(id),
    CONSTRAINT fk_checkout_user FOREIGN KEY (created_by_user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE orders (
    id CHAR(36) NOT NULL PRIMARY KEY,
    checkout_id CHAR(36) NOT NULL,
    buyer_company_id CHAR(36) NOT NULL,
    seller_company_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_status VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    buyer_note VARCHAR(1000) NULL,
    carrier VARCHAR(100) NULL,
    tracking_number VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_order_checkout FOREIGN KEY (checkout_id) REFERENCES order_checkouts(id),
    CONSTRAINT fk_order_buyer FOREIGN KEY (buyer_company_id) REFERENCES companies(id),
    CONSTRAINT fk_order_seller FOREIGN KEY (seller_company_id) REFERENCES companies(id),
    CONSTRAINT ck_order_status CHECK (status IN ('PLACED','ACCEPTED','REJECTED','CANCELLED','SHIPPED','DELIVERED')),
    CONSTRAINT ck_order_payment CHECK (payment_status IN ('UNPAID','PAID')),
    CONSTRAINT ck_order_parties CHECK (buyer_company_id <> seller_company_id),
    INDEX ix_order_buyer (buyer_company_id, created_at),
    INDEX ix_order_seller (seller_company_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Immutable snapshot of what was ordered; catalog changes never reach it.
CREATE TABLE order_lines (
    id CHAR(36) NOT NULL PRIMARY KEY,
    order_id CHAR(36) NOT NULL,
    line_no INT NOT NULL,
    product_id CHAR(36) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    unit_amount DECIMAL(19,2) NOT NULL,
    tax_included BOOLEAN NOT NULL,
    quantity INT NOT NULL,
    line_amount DECIMAL(19,2) NOT NULL,
    CONSTRAINT uq_order_line UNIQUE (order_id, line_no),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_order_line_quantity CHECK (quantity BETWEEN 1 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only history of every transition.
CREATE TABLE order_events (
    id CHAR(36) NOT NULL PRIMARY KEY,
    order_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    action VARCHAR(30) NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NOT NULL,
    note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_order_event_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_event_actor FOREIGN KEY (actor_user_id) REFERENCES app_users(id),
    INDEX ix_order_event_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

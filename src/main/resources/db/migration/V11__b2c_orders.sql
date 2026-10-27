-- ADR-010: consumer purchases at a separate, tax-inclusive retail price, on the same order tables as B2B.
ALTER TABLE catalog_products
    ADD COLUMN retail_amount DECIMAL(19,2) NULL,
    ADD COLUMN retail_currency CHAR(3) NULL,
    ADD CONSTRAINT ck_product_retail CHECK ((retail_amount IS NULL) = (retail_currency IS NULL));

-- A checkout belongs either to a buyer company or to a consumer.
ALTER TABLE order_checkouts
    MODIFY buyer_company_id CHAR(36) NULL,
    ADD COLUMN consumer_user_id CHAR(36) NULL,
    ADD CONSTRAINT fk_checkout_consumer FOREIGN KEY (consumer_user_id) REFERENCES app_users(id),
    ADD CONSTRAINT uq_checkout_consumer_key UNIQUE (consumer_user_id, idempotency_key),
    ADD CONSTRAINT ck_checkout_owner CHECK ((buyer_company_id IS NULL) <> (consumer_user_id IS NULL));

ALTER TABLE orders
    DROP CHECK ck_order_status,
    DROP CHECK ck_order_payment;
ALTER TABLE orders
    MODIFY buyer_company_id CHAR(36) NULL,
    ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'B2B',
    ADD COLUMN consumer_user_id CHAR(36) NULL,
    ADD COLUMN payment_method VARCHAR(20) NULL,
    ADD COLUMN ship_recipient VARCHAR(100) NULL,
    ADD COLUMN ship_phone VARCHAR(20) NULL,
    ADD COLUMN ship_address VARCHAR(300) NULL,
    ADD COLUMN ship_district VARCHAR(100) NULL,
    ADD COLUMN ship_city VARCHAR(100) NULL,
    ADD COLUMN ship_postal_code VARCHAR(10) NULL,
    ADD COLUMN terms_version VARCHAR(30) NULL,
    ADD COLUMN terms_accepted_at DATETIME(6) NULL,
    ADD COLUMN delivered_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_order_consumer FOREIGN KEY (consumer_user_id) REFERENCES app_users(id),
    ADD CONSTRAINT ck_order_status CHECK (status IN ('PLACED','ACCEPTED','REJECTED','CANCELLED','SHIPPED','DELIVERED','RETURN_REQUESTED','RETURNED')),
    ADD CONSTRAINT ck_order_payment CHECK (payment_status IN ('UNPAID','PAID','REFUNDED')),
    -- B2B rows keep exactly their old shape; a B2C row carries the consumer, payment method, address and consent.
    ADD CONSTRAINT ck_order_channel CHECK (
        (channel = 'B2B' AND buyer_company_id IS NOT NULL AND consumer_user_id IS NULL AND payment_method IS NULL)
        OR (channel = 'B2C' AND buyer_company_id IS NULL AND consumer_user_id IS NOT NULL
            AND payment_method IN ('BANK_TRANSFER','CASH_ON_DELIVERY') AND ship_recipient IS NOT NULL
            AND ship_address IS NOT NULL AND ship_city IS NOT NULL AND terms_accepted_at IS NOT NULL)),
    ADD INDEX ix_order_consumer (consumer_user_id, created_at);

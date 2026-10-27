-- ADR-011: per-channel seller shipping terms (TRY) and the order split into goods subtotal and shipping.
ALTER TABLE companies
    ADD COLUMN b2b_minimum_order DECIMAL(19,2) NULL,
    ADD COLUMN b2b_shipping_fee DECIMAL(19,2) NULL,
    ADD COLUMN b2b_free_shipping_from DECIMAL(19,2) NULL,
    ADD COLUMN b2c_minimum_order DECIMAL(19,2) NULL,
    ADD COLUMN b2c_shipping_fee DECIMAL(19,2) NULL,
    ADD COLUMN b2c_free_shipping_from DECIMAL(19,2) NULL,
    ADD CONSTRAINT ck_company_terms_positive CHECK (
        COALESCE(b2b_minimum_order,0) >= 0 AND COALESCE(b2b_shipping_fee,0) >= 0 AND COALESCE(b2b_free_shipping_from,0) >= 0
        AND COALESCE(b2c_minimum_order,0) >= 0 AND COALESCE(b2c_shipping_fee,0) >= 0 AND COALESCE(b2c_free_shipping_from,0) >= 0);
ALTER TABLE orders
    ADD COLUMN subtotal_amount DECIMAL(19,2) NULL,
    ADD COLUMN shipping_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
-- Orders before shipping terms: the whole total was goods.
UPDATE orders SET subtotal_amount = total_amount;
ALTER TABLE orders MODIFY subtotal_amount DECIMAL(19,2) NOT NULL;

-- ADR-011: platform commission recorded on every order (goods subtotal x rate); 0 while the first release is free.
ALTER TABLE orders
    ADD COLUMN commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0,
    ADD COLUMN commission_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_commission CHECK (commission_rate >= 0 AND commission_rate <= 0.5 AND commission_amount >= 0);

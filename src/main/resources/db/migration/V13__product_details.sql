-- ADR-011: VAT rate, sales unit, case pack, minimum quantity, barcode and brand; lines keep the VAT rate and unit.
ALTER TABLE catalog_products
    ADD COLUMN vat_rate INT NOT NULL DEFAULT 20,
    ADD COLUMN unit VARCHAR(10) NOT NULL DEFAULT 'PIECE',
    ADD COLUMN case_pack INT NOT NULL DEFAULT 1,
    ADD COLUMN min_quantity INT NOT NULL DEFAULT 1,
    ADD COLUMN barcode VARCHAR(14) NULL,
    ADD COLUMN brand VARCHAR(100) NULL,
    ADD CONSTRAINT ck_product_vat CHECK (vat_rate IN (0, 1, 10, 20)),
    ADD CONSTRAINT ck_product_unit CHECK (unit IN ('PIECE','PACK','BOX','KG','LITER','METER')),
    ADD CONSTRAINT ck_product_case_pack CHECK (case_pack BETWEEN 1 AND 10000),
    ADD CONSTRAINT ck_product_min_quantity CHECK (min_quantity BETWEEN 1 AND 100000);
ALTER TABLE order_lines
    ADD COLUMN vat_rate INT NOT NULL DEFAULT 20,
    ADD COLUMN unit VARCHAR(10) NOT NULL DEFAULT 'PIECE';

-- Seller payment account: part of the billing details a platform admin checks, shown to buyers who pay by transfer.
ALTER TABLE companies
    ADD COLUMN iban VARCHAR(26) NULL,
    ADD CONSTRAINT ck_company_iban CHECK (iban IS NULL OR iban REGEXP '^TR[0-9]{24}$');

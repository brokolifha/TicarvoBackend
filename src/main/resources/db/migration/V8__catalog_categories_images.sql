-- ADR-007: platform category tree (2 levels), product category and product images.
CREATE TABLE catalog_categories (
    id CHAR(36) NOT NULL PRIMARY KEY,
    parent_id CHAR(36) NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(100) NOT NULL,
    position INT NOT NULL,
    CONSTRAINT uq_category_code UNIQUE (code),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES catalog_categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO catalog_categories (id, parent_id, code, name, position) VALUES
    (UUID(), NULL, 'gida-icecek', 'Gıda ve İçecek', 1),
    (UUID(), NULL, 'temizlik-hijyen', 'Temizlik ve Hijyen', 2),
    (UUID(), NULL, 'ambalaj', 'Ambalaj ve Paketleme', 3),
    (UUID(), NULL, 'ofis-kirtasiye', 'Ofis ve Kırtasiye', 4),
    (UUID(), NULL, 'yapi-hirdavat', 'Yapı ve Hırdavat', 5),
    (UUID(), NULL, 'elektronik', 'Elektronik', 6),
    (UUID(), NULL, 'tekstil-giyim', 'Tekstil ve Giyim', 7),
    (UUID(), NULL, 'horeca', 'Otel, Restoran ve Kafe', 8);

INSERT INTO catalog_categories (id, parent_id, code, name, position)
SELECT UUID(), parent.id, child.code, child.name, child.position
FROM (
    SELECT 'gida-icecek' AS parent_code, 'temel-gida' AS code, 'Temel Gıda' AS name, 1 AS position
    UNION ALL SELECT 'gida-icecek', 'icecek', 'İçecekler', 2
    UNION ALL SELECT 'gida-icecek', 'atistirmalik', 'Atıştırmalık ve Şekerleme', 3
    UNION ALL SELECT 'gida-icecek', 'taze-dondurulmus', 'Taze ve Dondurulmuş Gıda', 4
    UNION ALL SELECT 'temizlik-hijyen', 'ev-temizligi', 'Ev Temizliği', 1
    UNION ALL SELECT 'temizlik-hijyen', 'kisisel-bakim', 'Kişisel Bakım', 2
    UNION ALL SELECT 'temizlik-hijyen', 'kagit-urunleri', 'Kağıt Ürünleri', 3
    UNION ALL SELECT 'temizlik-hijyen', 'endustriyel-temizlik', 'Endüstriyel Temizlik', 4
    UNION ALL SELECT 'ambalaj', 'karton-kutu', 'Karton ve Kutu', 1
    UNION ALL SELECT 'ambalaj', 'plastik-ambalaj', 'Plastik Ambalaj', 2
    UNION ALL SELECT 'ambalaj', 'etiket-bant', 'Etiket ve Bant', 3
    UNION ALL SELECT 'ambalaj', 'dolgu-koruma', 'Dolgu ve Koruma Malzemesi', 4
    UNION ALL SELECT 'ofis-kirtasiye', 'kirtasiye', 'Kırtasiye', 1
    UNION ALL SELECT 'ofis-kirtasiye', 'ofis-mobilyasi', 'Ofis Mobilyası', 2
    UNION ALL SELECT 'ofis-kirtasiye', 'yazici-sarf', 'Yazıcı ve Sarf Malzemesi', 3
    UNION ALL SELECT 'ofis-kirtasiye', 'dosyalama-arsiv', 'Dosyalama ve Arşiv', 4
    UNION ALL SELECT 'yapi-hirdavat', 'el-aletleri', 'El Aletleri', 1
    UNION ALL SELECT 'yapi-hirdavat', 'elektrik-malzemesi', 'Elektrik Malzemesi', 2
    UNION ALL SELECT 'yapi-hirdavat', 'tesisat', 'Tesisat', 3
    UNION ALL SELECT 'yapi-hirdavat', 'insaat-malzemesi', 'İnşaat Malzemesi', 4
    UNION ALL SELECT 'elektronik', 'bilgisayar', 'Bilgisayar ve Aksesuar', 1
    UNION ALL SELECT 'elektronik', 'telefon', 'Telefon ve Aksesuar', 2
    UNION ALL SELECT 'elektronik', 'ev-aletleri', 'Beyaz Eşya ve Küçük Ev Aletleri', 3
    UNION ALL SELECT 'elektronik', 'guvenlik-sistemleri', 'Güvenlik Sistemleri', 4
    UNION ALL SELECT 'tekstil-giyim', 'is-kiyafeti-kkd', 'İş Kıyafeti ve KKD', 1
    UNION ALL SELECT 'tekstil-giyim', 'giyim', 'Giyim', 2
    UNION ALL SELECT 'tekstil-giyim', 'ev-tekstili', 'Ev Tekstili', 3
    UNION ALL SELECT 'tekstil-giyim', 'kumas-iplik', 'Kumaş ve İplik', 4
    UNION ALL SELECT 'horeca', 'mutfak-ekipmani', 'Mutfak Ekipmanı', 1
    UNION ALL SELECT 'horeca', 'servis-urunleri', 'Servis Ürünleri', 2
    UNION ALL SELECT 'horeca', 'tek-kullanimlik', 'Tek Kullanımlık Ürünler', 3
    UNION ALL SELECT 'horeca', 'konaklama', 'Konaklama Malzemeleri', 4
) AS child
JOIN (SELECT id, code FROM catalog_categories) AS parent ON parent.code = child.parent_code;

ALTER TABLE catalog_products
    ADD COLUMN category_id CHAR(36) NULL,
    ADD CONSTRAINT fk_catalog_category FOREIGN KEY (category_id) REFERENCES catalog_categories(id),
    ADD INDEX ix_catalog_category (category_id);

-- Positions are kept contiguous (0 = cover) by the service under the seller lock.
CREATE TABLE catalog_product_images (
    id CHAR(36) NOT NULL PRIMARY KEY,
    product_id CHAR(36) NOT NULL,
    seller_company_id CHAR(36) NOT NULL,
    position INT NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    size_bytes INT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_image_product FOREIGN KEY (product_id, seller_company_id) REFERENCES catalog_products(id, seller_company_id),
    CONSTRAINT ck_image_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_image_position CHECK (position BETWEEN 0 AND 4),
    INDEX ix_image_product (product_id, position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

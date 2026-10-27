package com.ticarvo.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Product image metadata; the bytes live in the image store under {@link #storageKey()}. */
@Entity @Table(name = "catalog_product_images")
public class ProductImage {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "product_id", length = 36, nullable = false) private UUID productId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "seller_company_id", length = 36, nullable = false) private UUID sellerCompanyId;
    @Column(nullable = false) private int position;
    @Column(name = "content_type", length = 20, nullable = false) private String contentType;
    @Column(name = "size_bytes", nullable = false) private int sizeBytes;
    @Column(nullable = false) private int width;
    @Column(nullable = false) private int height;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ProductImage() {}
    public ProductImage(UUID productId, UUID sellerCompanyId, int position, String contentType, int sizeBytes, int width, int height, Instant now) {
        this.id = UUID.randomUUID();
        this.productId = productId; this.sellerCompanyId = sellerCompanyId; this.position = position;
        this.contentType = contentType; this.sizeBytes = sizeBytes; this.width = width; this.height = height;
        this.createdAt = now;
    }
    public UUID id() { return id; }
    public UUID productId() { return productId; }
    public int position() { return position; }
    public String contentType() { return contentType; }
    public void moveTo(int position) { this.position = position; }
    /** Built only from server-generated UUIDs, never from client input. */
    public String storageKey() {
        return sellerCompanyId + "/" + productId + "/" + id + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }
}

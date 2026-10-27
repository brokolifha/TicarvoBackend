package com.ticarvo.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="catalog_product_buyers") @IdClass(ProductGrant.Key.class)
public class ProductGrant {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name="product_id",length=36) private UUID productId;
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name="buyer_company_id",length=36) private UUID buyerCompanyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="seller_company_id",length=36,nullable=false) private UUID sellerCompanyId;
    @Column(name="created_at",nullable=false) private Instant createdAt;

    protected ProductGrant() {}
    public ProductGrant(UUID product,UUID seller,UUID buyer,Instant now) {
        productId=product;
        sellerCompanyId=seller;
        buyerCompanyId=buyer;
        createdAt=now;
    }
    public record Key(UUID productId,UUID buyerCompanyId) implements Serializable {}
}

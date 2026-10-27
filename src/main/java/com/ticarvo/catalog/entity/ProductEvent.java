package com.ticarvo.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="catalog_product_events")
public class ProductEvent {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="product_id",length=36,nullable=false) private UUID productId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="actor_user_id",length=36,nullable=false) private UUID actorUserId;
    @Column(length=32,nullable=false) private String action;
    @Column(name="product_version",nullable=false) private long productVersion;
    @Column(name="created_at",nullable=false) private Instant createdAt;

    protected ProductEvent() {}
    public ProductEvent(Product product,UUID actor,String action,Instant now) {
        id=UUID.randomUUID();
        productId=product.id();
        actorUserId=actor;
        this.action=action;
        productVersion=product.version();
        createdAt=now;
    }
}

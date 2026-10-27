package com.ticarvo.order.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Owned by either a buyer company (B2B) or a consumer (B2C), never both. */
@Entity @Table(name = "order_checkouts")
public class Checkout {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "buyer_company_id", length = 36)
    private UUID buyerCompanyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "consumer_user_id", length = 36)
    private UUID consumerUserId;
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_user_id", nullable = false, length = 36)
    private UUID createdByUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected Checkout() {}
    public Checkout(UUID buyerCompanyId, UUID consumerUserId, String idempotencyKey, String requestHash, UUID createdByUserId, Instant now) {
        this.id = UUID.randomUUID(); this.buyerCompanyId = buyerCompanyId; this.consumerUserId = consumerUserId; this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash; this.createdByUserId = createdByUserId; this.createdAt = now;
    }
    public UUID id() { return id; }
    public String requestHash() { return requestHash; }
}

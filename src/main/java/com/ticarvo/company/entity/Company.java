package com.ticarvo.company.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "companies")
public class Company {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "public_seller", nullable = false)
    private boolean publicSeller;
    @Column(name = "suspended_at")
    private Instant suspendedAt;
    // ADR-011 shipping terms in TRY, per channel; null means no rule.
    @Column(name = "b2b_minimum_order", precision = 19, scale = 2) private java.math.BigDecimal b2bMinimumOrder;
    @Column(name = "b2b_shipping_fee", precision = 19, scale = 2) private java.math.BigDecimal b2bShippingFee;
    @Column(name = "b2b_free_shipping_from", precision = 19, scale = 2) private java.math.BigDecimal b2bFreeShippingFrom;
    @Column(name = "b2c_minimum_order", precision = 19, scale = 2) private java.math.BigDecimal b2cMinimumOrder;
    @Column(name = "b2c_shipping_fee", precision = 19, scale = 2) private java.math.BigDecimal b2cShippingFee;
    @Column(name = "b2c_free_shipping_from", precision = 19, scale = 2) private java.math.BigDecimal b2cFreeShippingFrom;
    /** Order: B2B minimum, fee, free-from, then the same for B2C. */
    public java.math.BigDecimal[] shippingTerms() {
        return new java.math.BigDecimal[]{b2bMinimumOrder, b2bShippingFee, b2bFreeShippingFrom, b2cMinimumOrder, b2cShippingFee, b2cFreeShippingFrom};
    }
    public void shippingTerms(java.math.BigDecimal[] t) {
        b2bMinimumOrder = t[0]; b2bShippingFee = t[1]; b2bFreeShippingFrom = t[2]; b2cMinimumOrder = t[3]; b2cShippingFee = t[4]; b2cFreeShippingFrom = t[5];
    }
    public boolean isSuspended() { return suspendedAt != null; }
    public void suspend(Instant now) { suspendedAt = now; }
    public void restore() { suspendedAt = null; }
    public boolean isPublicSeller() { return publicSeller; }
    public void setPublicSeller(boolean value) { publicSeller = value; }
    protected Company() {}
    public Company(String name) { this.id = UUID.randomUUID(); this.name = name; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}

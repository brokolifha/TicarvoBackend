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
    // ADR-011 profile and seller verification.
    @Column(name = "legal_name", length = 200) private String legalName;
    @Column(name = "tax_id", length = 11) private String taxId;
    @Column(name = "tax_office", length = 100) private String taxOffice;
    @Column(length = 500) private String address;
    @Column(length = 60) private String city;
    @Column(length = 20) private String phone;
    @Column(length = 26) private String iban;
    @Column(length = 2000) private String description;
    @Column(length = 200) private String website;
    @Column(name = "logo_key", length = 120) private String logoKey;
    @Enumerated(EnumType.STRING) @Column(name = "seller_status", nullable = false, length = 10)
    private com.ticarvo.company.model.SellerStatus sellerStatus = com.ticarvo.company.model.SellerStatus.NONE;
    @Column(name = "seller_note", length = 500) private String sellerNote;
    @Column(name = "seller_applied_at") private Instant sellerAppliedAt;
    @Column(name = "selling_enabled", nullable = false) private boolean sellingEnabled;
    /** Order: legal name, tax id, tax office, address, city, phone. */
    public String[] billing() { return new String[]{legalName, taxId, taxOffice, address, city, phone, iban}; }
    public void billing(String[] b) { legalName = b[0]; taxId = b[1]; taxOffice = b[2]; address = b[3]; city = b[4]; phone = b[5]; iban = b[6]; }
    public String getIban() { return iban; }
    public void showcase(String description, String website) { this.description = description; this.website = website; }
    public String getDescription() { return description; }
    public String getWebsite() { return website; }
    public String getCity() { return city; }
    public String getLogoKey() { return logoKey; }
    public void setLogoKey(String key) { logoKey = key; }
    public com.ticarvo.company.model.SellerStatus getSellerStatus() { return sellerStatus; }
    public String getSellerNote() { return sellerNote; }
    public Instant getSellerAppliedAt() { return sellerAppliedAt; }
    public boolean isSellingEnabled() { return sellingEnabled; }
    public void seller(com.ticarvo.company.model.SellerStatus status, String note, Instant appliedAt, boolean enabled) {
        sellerStatus = status; sellerNote = note; sellerAppliedAt = appliedAt; sellingEnabled = enabled;
    }
    public boolean isSuspended() { return suspendedAt != null; }
    public void suspend(Instant now) { suspendedAt = now; }
    public void restore() { suspendedAt = null; }
    public boolean isPublicSeller() { return publicSeller; }
    public void setPublicSeller(boolean value) { publicSeller = value; }
    protected Company() {}
    public Company(String name, boolean sellingEnabled) {
        this.id = UUID.randomUUID(); this.name = name; this.createdAt = Instant.now(); this.sellingEnabled = sellingEnabled;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}

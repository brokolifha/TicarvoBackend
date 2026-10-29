package com.ticarvo.admin.entity;

import com.ticarvo.admin.model.Moderation.PolicyReason;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** ADR-013: an anonymous notice waiting for its sender to confirm the mailed code. */
@Entity @Table(name = "pending_notices")
public class PendingNotice {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @Column(name = "reporter_name", nullable = false, length = 100)
    private String reporterName;
    @Column(name = "reporter_email", nullable = false, length = 254)
    private String reporterEmail;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "company_id", nullable = false, length = 36)
    private UUID companyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "product_id", length = 36)
    private UUID productId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private PolicyReason reason;
    @Column(nullable = false, length = 2000)
    private String description;
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    protected PendingNotice() {}
    public PendingNotice(String name, String email, UUID companyId, UUID productId, PolicyReason reason, String description,
            String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.reporterName = name; this.reporterEmail = email; this.companyId = companyId;
        this.productId = productId; this.reason = reason; this.description = description; this.tokenHash = tokenHash;
        this.createdAt = now; this.expiresAt = expiresAt;
    }
    public String reporterName() { return reporterName; }
    public String reporterEmail() { return reporterEmail; }
    public UUID companyId() { return companyId; }
    public UUID productId() { return productId; }
    public PolicyReason reason() { return reason; }
    public String description() { return description; }
    public Instant expiresAt() { return expiresAt; }
}

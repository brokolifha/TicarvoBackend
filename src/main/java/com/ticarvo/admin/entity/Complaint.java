package com.ticarvo.admin.entity;

import com.ticarvo.admin.model.Moderation.ComplaintStatus;
import com.ticarvo.admin.model.Moderation.PolicyReason;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "complaints")
public class Complaint {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "reporter_user_id", nullable = false, length = 36)
    private UUID reporterUserId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "company_id", nullable = false, length = 36)
    private UUID companyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "product_id", length = 36)
    private UUID productId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private PolicyReason reason;
    @Column(nullable = false, length = 2000)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ComplaintStatus status;
    @Column(name = "admin_note", length = 2000)
    private String adminNote;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "reviewer_user_id", length = 36)
    private UUID reviewerUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Version @Column(nullable = false)
    private long version;
    protected Complaint() {}
    public Complaint(UUID reporterUserId, UUID companyId, UUID productId, PolicyReason reason, String description, Instant now) {
        this.id = UUID.randomUUID(); this.reporterUserId = reporterUserId; this.companyId = companyId; this.productId = productId;
        this.reason = reason; this.description = description; this.status = ComplaintStatus.OPEN; this.createdAt = now;
    }
    public void startReview(UUID admin) { status = ComplaintStatus.IN_REVIEW; reviewerUserId = admin; }
    public void resolve(UUID admin, ComplaintStatus outcome, String note, Instant now) {
        status = outcome; reviewerUserId = admin; adminNote = note; decidedAt = now;
    }
    public UUID id() { return id; }
    public UUID reporterUserId() { return reporterUserId; }
    public UUID companyId() { return companyId; }
    public UUID productId() { return productId; }
    public PolicyReason reason() { return reason; }
    public String description() { return description; }
    public ComplaintStatus status() { return status; }
    public String adminNote() { return adminNote; }
    public UUID reviewerUserId() { return reviewerUserId; }
    public Instant createdAt() { return createdAt; }
    public Instant decidedAt() { return decidedAt; }
    public long version() { return version; }
}

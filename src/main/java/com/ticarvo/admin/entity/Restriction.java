package com.ticarvo.admin.entity;

import com.ticarvo.admin.model.Moderation.PolicyReason;
import com.ticarvo.admin.model.Moderation.RestrictionAction;
import com.ticarvo.admin.model.Moderation.TargetType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** A suspend or restore decision with its statement of reasons; rows are never updated except to spend the appeal code. */
@Entity @Table(name = "moderation_restrictions")
public class Restriction {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "target_id", nullable = false, length = 36)
    private UUID targetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RestrictionAction action;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private PolicyReason reason;
    @Column(nullable = false, length = 2000)
    private String statement;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "complaint_id", length = 36)
    private UUID complaintId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "admin_user_id", nullable = false, length = 36)
    private UUID adminUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "appeal_token_hash", length = 64)
    private String appealTokenHash;
    @Column(name = "appeal_until")
    private Instant appealUntil;
    protected Restriction() {}
    public Restriction(TargetType targetType, UUID targetId, RestrictionAction action, PolicyReason reason, String statement,
            UUID complaintId, UUID adminUserId, Instant now, String appealTokenHash, Instant appealUntil) {
        this.id = UUID.randomUUID(); this.targetType = targetType; this.targetId = targetId; this.action = action;
        this.reason = reason; this.statement = statement; this.complaintId = complaintId; this.adminUserId = adminUserId;
        this.createdAt = now; this.appealTokenHash = appealTokenHash; this.appealUntil = appealUntil;
    }
    public boolean appealOpen(Instant now) { return appealTokenHash != null && now.isBefore(appealUntil); }
    public void spendAppealCode() { appealTokenHash = null; }
    public UUID id() { return id; }
    public TargetType targetType() { return targetType; }
    public UUID targetId() { return targetId; }
    public RestrictionAction action() { return action; }
    public PolicyReason reason() { return reason; }
    public String statement() { return statement; }
    public UUID complaintId() { return complaintId; }
    public Instant createdAt() { return createdAt; }
    public Instant appealUntil() { return appealUntil; }
}

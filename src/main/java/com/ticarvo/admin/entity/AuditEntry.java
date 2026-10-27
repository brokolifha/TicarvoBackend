package com.ticarvo.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Append-only: nothing updates or deletes these rows. targetType is USER, COMPANY, COMPLAINT or APPEAL. */
@Entity @Table(name = "admin_audit_log")
public class AuditEntry {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_user_id", length = 36)
    private UUID actorUserId;
    @Column(nullable = false, length = 40)
    private String action;
    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "target_id", nullable = false, length = 36)
    private UUID targetId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected AuditEntry() {}
    public AuditEntry(UUID actorUserId, String action, String targetType, UUID targetId, Instant createdAt) {
        this.id = UUID.randomUUID(); this.actorUserId = actorUserId; this.action = action;
        this.targetType = targetType; this.targetId = targetId; this.createdAt = createdAt;
    }
}

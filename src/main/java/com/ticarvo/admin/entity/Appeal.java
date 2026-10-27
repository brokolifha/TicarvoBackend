package com.ticarvo.admin.entity;

import com.ticarvo.admin.model.Moderation.AppealStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "moderation_appeals")
public class Appeal {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "restriction_id", nullable = false, length = 36)
    private UUID restrictionId;
    @Column(nullable = false, length = 2000)
    private String text;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AppealStatus status;
    @Column(name = "decision_note", length = 2000)
    private String decisionNote;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "decided_by_user_id", length = 36)
    private UUID decidedByUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Version @Column(nullable = false)
    private long version;
    protected Appeal() {}
    public Appeal(UUID restrictionId, String text, Instant now) {
        this.id = UUID.randomUUID(); this.restrictionId = restrictionId; this.text = text;
        this.status = AppealStatus.PENDING; this.createdAt = now;
    }
    public void decide(UUID admin, AppealStatus outcome, String note, Instant now) {
        status = outcome; decidedByUserId = admin; decisionNote = note; decidedAt = now;
    }
    public UUID id() { return id; }
    public UUID restrictionId() { return restrictionId; }
    public String text() { return text; }
    public AppealStatus status() { return status; }
    public String decisionNote() { return decisionNote; }
    public Instant createdAt() { return createdAt; }
    public Instant decidedAt() { return decidedAt; }
    public long version() { return version; }
}

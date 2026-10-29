package com.ticarvo.notification.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** ADR-014: one notice for one person; companyId is the workspace its link opens in, null for personal ones. */
@Entity @Table(name = "notifications")
public class Notification {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) private UUID userId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "company_id", length = 36) private UUID companyId;
    @Column(nullable = false, length = 300) private String text;
    @Column(nullable = false, length = 200) private String link;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "read_at") private Instant readAt;
    protected Notification() {}
    public Notification(UUID userId, UUID companyId, String text, String link, Instant now) {
        this.id = UUID.randomUUID(); this.userId = userId; this.companyId = companyId;
        this.text = text.length() > 300 ? text.substring(0, 300) : text; this.link = link; this.createdAt = now;
    }
    public void markRead(Instant now) { if (readAt == null) readAt = now; }
    public UUID id() { return id; }
    public UUID companyId() { return companyId; }
    public String text() { return text; }
    public String link() { return link; }
    public Instant createdAt() { return createdAt; }
    public boolean read() { return readAt != null; }
}

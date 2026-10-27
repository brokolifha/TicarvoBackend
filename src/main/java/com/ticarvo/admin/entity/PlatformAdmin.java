package com.ticarvo.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "platform_admins")
public class PlatformAdmin {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", length = 36)
    private UUID userId;
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;
    protected PlatformAdmin() {}
    public PlatformAdmin(UUID userId, Instant grantedAt) { this.userId = userId; this.grantedAt = grantedAt; }
}

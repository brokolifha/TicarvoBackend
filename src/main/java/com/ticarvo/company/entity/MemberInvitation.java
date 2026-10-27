package com.ticarvo.company.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Pending invitation of an email address to join a company (ADR-006); only the code's SHA-256 is stored. */
@Entity @Table(name = "company_member_invitations")
public class MemberInvitation {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "company_id", length = 36, nullable = false) private UUID companyId;
    @Column(length = 254, nullable = false) private String email;
    @Column(length = 30, nullable = false) private String role;
    @Column(name = "token_hash", length = 64, nullable = false) private String tokenHash;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "invited_by_user_id", length = 36, nullable = false) private UUID invitedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    protected MemberInvitation() {}
    public MemberInvitation(UUID companyId, String email, String role, String tokenHash, UUID invitedBy, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.companyId = companyId; this.email = email; this.role = role;
        this.tokenHash = tokenHash; this.invitedBy = invitedBy; this.createdAt = now; this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isValidAt(Instant now) { return now.isBefore(expiresAt); }
}

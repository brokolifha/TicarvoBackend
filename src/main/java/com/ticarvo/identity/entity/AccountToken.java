package com.ticarvo.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Single-use email verification or password reset token; only its SHA-256 hash is stored. */
@Entity @Table(name = "account_tokens")
public class AccountToken {
    public enum Purpose { EMAIL_VERIFICATION, PASSWORD_RESET, MFA_RECOVERY }
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", length = 36, nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) private Purpose purpose;
    @Column(name = "token_hash", length = 64, nullable = false) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    protected AccountToken() {}
    public AccountToken(UUID userId, Purpose purpose, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.userId = userId; this.purpose = purpose;
        this.tokenHash = tokenHash; this.createdAt = now; this.expiresAt = expiresAt;
    }
    public UUID getUserId() { return userId; }
    public boolean isValidAt(Instant now) { return now.isBefore(expiresAt); }}

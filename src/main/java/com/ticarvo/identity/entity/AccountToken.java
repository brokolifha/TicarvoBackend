package com.ticarvo.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Single-use email verification or password reset token; only its SHA-256 hash is stored. */
@Entity @Table(name = "account_tokens")
public class AccountToken {
    public enum Purpose { EMAIL_VERIFICATION, PASSWORD_RESET, MFA_RECOVERY, EMAIL_CHANGE }
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", length = 36, nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) private Purpose purpose;
    @Column(name = "token_hash", length = 64, nullable = false) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    /** Only for EMAIL_CHANGE: the address the code was sent to (C-03). */
    @Column(name = "new_email", length = 254) private String newEmail;
    protected AccountToken() {}
    public AccountToken(UUID userId, Purpose purpose, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.userId = userId; this.purpose = purpose;
        this.tokenHash = tokenHash; this.createdAt = now; this.expiresAt = expiresAt;
    }
    public static AccountToken emailChange(UUID userId, String newEmail, String tokenHash, Instant now, Instant expiresAt) {
        var token = new AccountToken(userId, Purpose.EMAIL_CHANGE, tokenHash, now, expiresAt);
        token.newEmail = newEmail;
        return token;
    }
    public String getNewEmail() { return newEmail; }
    public UUID getUserId() { return userId; }
    public boolean isValidAt(Instant now) { return now.isBefore(expiresAt); }}

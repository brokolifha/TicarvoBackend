package com.ticarvo.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "app_users")
public class AppUser {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @Column(nullable = false, length = 254, unique = true)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;
    @Column(name = "mfa_secret", length = 100)
    private String mfaSecret;
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;
    @Column(name = "suspended_at")
    private Instant suspendedAt;
    @Column(name = "deleted_at") private Instant deletedAt;
    protected AppUser() {}
    public AppUser(String email, String passwordHash, String displayName) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = passwordHash;
        this.displayName = displayName; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isSuspended() { return suspendedAt != null; }
    /**
     * ADR-013 (KVKK art. 7): identifying data goes; the row stays because orders and audit entries point at it. The
     * address cannot sign in again: nobody knows it or the random password.
     */
    public void anonymize(String unusablePasswordHash, Instant now) {
        email = "deleted-" + id + "@deleted.invalid"; displayName = "Silinmiş hesap"; passwordHash = unusablePasswordHash;
        mfaSecret = null; mfaEnabled = false; mfaLastStep = null; deletedAt = now;
    }
    public boolean isDeleted() { return deletedAt != null; }
    public void suspend(Instant now) { suspendedAt = now; }
    public void restore() { suspendedAt = null; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public void markEmailVerified(Instant now) { if (emailVerifiedAt == null) emailVerifiedAt = now; }
    public void changePassword(String passwordHash) { this.passwordHash = passwordHash; }
    public void rename(String displayName) { this.displayName = displayName; }
    /** The new address was proven by a code mailed to it, so it counts as verified. */
    public void changeEmail(String email, Instant now) { this.email = email; this.emailVerifiedAt = now; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public String getMfaSecret() { return mfaSecret; }
    public void startMfaSetup(String encryptedSecret) { mfaSecret = encryptedSecret; mfaEnabled = false; mfaLastStep = null; }
    public void enableMfa() { mfaEnabled = true; }
    public void disableMfa() { mfaSecret = null; mfaEnabled = false; mfaLastStep = null; }
    /** Accepts each TOTP time step at most once (replay protection). */
    public boolean acceptMfaStep(long step) {
        if (mfaLastStep != null && step <= mfaLastStep) return false;
        mfaLastStep = step;
        return true;
    }
}

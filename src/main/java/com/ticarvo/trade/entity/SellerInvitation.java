package com.ticarvo.trade.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="seller_invitations")
public class SellerInvitation {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name="seller_company_id",length=36) private UUID sellerCompanyId;
    @Column(name="code_hash",length=64,nullable=false) private String codeHash;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="revoked_at") private Instant revokedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="created_by_user_id",length=36,nullable=false) private UUID createdByUserId;
    protected SellerInvitation() {}
    public SellerInvitation(UUID seller) { sellerCompanyId=seller; }
    public void rotate(String hash, UUID actor, Instant now, Instant expiry) {
        codeHash=hash; createdByUserId=actor; createdAt=now; expiresAt=expiry; revokedAt=null;
    }
    public void revoke(Instant now) { if(revokedAt==null) revokedAt=now; }
    public boolean valid(String hash, Instant now) { return revokedAt==null && now.isBefore(expiresAt) && codeHash.equals(hash); }
    public UUID sellerId() { return sellerCompanyId; }
    public Instant expiresAt() { return expiresAt; }
}

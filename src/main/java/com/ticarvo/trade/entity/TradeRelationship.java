package com.ticarvo.trade.entity;
import com.ticarvo.trade.model.TradeStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="trade_relationships")
public class TradeRelationship {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="buyer_company_id", length=36, nullable=false) private UUID buyerCompanyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="seller_company_id", length=36, nullable=false) private UUID sellerCompanyId;
    @Enumerated(EnumType.STRING) @Column(length=20, nullable=false) private TradeStatus status;
    @Version @Column(nullable=false) private long version;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="requested_by_user_id", length=36, nullable=false) private UUID requestedByUserId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="last_action_by_user_id", length=36, nullable=false) private UUID lastActionByUserId;
    protected TradeRelationship() {}
    public TradeRelationship(UUID buyer, UUID seller, UUID actor, Instant now) {
        id=UUID.randomUUID(); buyerCompanyId=buyer; sellerCompanyId=seller; status=TradeStatus.PENDING;
        createdAt=now; updatedAt=now; requestedByUserId=actor; lastActionByUserId=actor;
    }
    public void transition(TradeStatus next, UUID actor, Instant now) { status=next; lastActionByUserId=actor; updatedAt=now; }
    public UUID id() { return id; }
    public UUID buyerId() { return buyerCompanyId; }
    public UUID sellerId() { return sellerCompanyId; }
    public TradeStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}

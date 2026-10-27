package com.ticarvo.trade.entity;
import com.ticarvo.trade.model.TradeStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="trade_relationship_events")
public class TradeEvent {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="relationship_id",length=36,nullable=false) private UUID relationshipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="actor_user_id",length=36,nullable=false) private UUID actorUserId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="actor_company_id",length=36,nullable=false) private UUID actorCompanyId;
    @Enumerated(EnumType.STRING) @Column(name="from_status",length=20) private TradeStatus fromStatus;
    @Enumerated(EnumType.STRING) @Column(name="to_status",length=20,nullable=false) private TradeStatus toStatus;
    @Column(length=20,nullable=false) private String action;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="relationship_version",nullable=false) private long relationshipVersion;
    protected TradeEvent() {}
    public TradeEvent(TradeRelationship relationship, UUID user, UUID company, TradeStatus from, String action, Instant now) {
        id=UUID.randomUUID(); relationshipId=relationship.id(); actorUserId=user; actorCompanyId=company;
        fromStatus=from; toStatus=relationship.status(); this.action=action; occurredAt=now; relationshipVersion=relationship.version();
    }
}

package com.ticarvo.order.entity;

import com.ticarvo.order.model.OrderStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Immutable @Table(name = "order_events")
public class OrderEvent {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "order_id", nullable = false, length = 36)
    private UUID orderId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_user_id", nullable = false, length = 36)
    private UUID actorUserId;
    @Column(nullable = false, length = 30)
    private String action;
    @Enumerated(EnumType.STRING) @Column(name = "from_status", length = 20)
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING) @Column(name = "to_status", nullable = false, length = 20)
    private OrderStatus toStatus;
    @Column(length = 1000)
    private String note;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected OrderEvent() {}
    public OrderEvent(UUID orderId, UUID actorUserId, String action, OrderStatus fromStatus, OrderStatus toStatus, String note, Instant now) {
        this.id = UUID.randomUUID(); this.orderId = orderId; this.actorUserId = actorUserId; this.action = action;
        this.fromStatus = fromStatus; this.toStatus = toStatus; this.note = note; this.createdAt = now;
    }
    public String action() { return action; }
    public OrderStatus fromStatus() { return fromStatus; }
    public OrderStatus toStatus() { return toStatus; }
    public String note() { return note; }
    public Instant createdAt() { return createdAt; }
}

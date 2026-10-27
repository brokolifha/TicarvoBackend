package com.ticarvo.order.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.util.UUID;

/** Snapshot taken when the order was placed; later catalog changes never reach it. */
@Entity @Immutable @Table(name = "order_lines")
public class OrderLine {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "order_id", nullable = false, length = 36)
    private UUID orderId;
    @Column(name = "line_no", nullable = false)
    private int lineNo;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "product_id", nullable = false, length = 36)
    private UUID productId;
    @Column(nullable = false, length = 64)
    private String sku;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "unit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitAmount;
    @Column(name = "tax_included", nullable = false)
    private boolean taxIncluded;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "line_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineAmount;
    protected OrderLine() {}
    public OrderLine(UUID orderId, int lineNo, UUID productId, String sku, String name, BigDecimal unitAmount, boolean taxIncluded, int quantity, BigDecimal lineAmount) {
        this.id = UUID.randomUUID(); this.orderId = orderId; this.lineNo = lineNo; this.productId = productId; this.sku = sku; this.name = name;
        this.unitAmount = unitAmount; this.taxIncluded = taxIncluded; this.quantity = quantity; this.lineAmount = lineAmount;
    }
    public UUID orderId() { return orderId; }
    public int lineNo() { return lineNo; }
    public UUID productId() { return productId; }
    public String sku() { return sku; }
    public String name() { return name; }
    public BigDecimal unitAmount() { return unitAmount; }
    public boolean taxIncluded() { return taxIncluded; }
    public int quantity() { return quantity; }
    public BigDecimal lineAmount() { return lineAmount; }
}

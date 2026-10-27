package com.ticarvo.order.entity;

import com.ticarvo.order.model.Channel;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.model.PaymentMethod;
import com.ticarvo.order.model.PaymentStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Named PurchaseOrder because ORDER is a reserved word in JPQL. B2B rows have a buyer company, B2C rows a consumer. */
@Entity @Table(name = "orders")
public class PurchaseOrder {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "checkout_id", nullable = false, length = 36)
    private UUID checkoutId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Channel channel;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "buyer_company_id", length = 36)
    private UUID buyerCompanyId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "consumer_user_id", length = 36)
    private UUID consumerUserId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "seller_company_id", nullable = false, length = 36)
    private UUID sellerCompanyId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OrderStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;
    @Enumerated(EnumType.STRING) @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotalAmount;
    @Column(name = "shipping_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal shippingAmount = BigDecimal.ZERO;
    @Column(name = "buyer_note", length = 1000)
    private String buyerNote;
    @Column(length = 100)
    private String carrier;
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;
    @Embedded
    private ShippingAddress shippingAddress;
    @Column(name = "terms_version", length = 30)
    private String termsVersion;
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version @Column(nullable = false)
    private long version;
    protected PurchaseOrder() {}
    public PurchaseOrder(UUID checkoutId, UUID buyerCompanyId, UUID sellerCompanyId, String currency, BigDecimal totalAmount, String buyerNote, Instant now) {
        this.id = UUID.randomUUID(); this.checkoutId = checkoutId; this.channel = Channel.B2B; this.buyerCompanyId = buyerCompanyId;
        this.sellerCompanyId = sellerCompanyId; this.status = OrderStatus.PLACED; this.paymentStatus = PaymentStatus.UNPAID;
        this.currency = currency; this.totalAmount = totalAmount; this.subtotalAmount = totalAmount; this.buyerNote = buyerNote; this.createdAt = now; this.updatedAt = now;
    }
    /** A consumer order: address, payment method and the accepted terms version are copied onto the order (ADR-010). */
    public static PurchaseOrder consumer(UUID checkoutId, UUID consumerUserId, UUID sellerCompanyId, String currency, BigDecimal totalAmount,
            String buyerNote, PaymentMethod method, ShippingAddress address, String termsVersion, Instant now) {
        var order = new PurchaseOrder(checkoutId, null, sellerCompanyId, currency, totalAmount, buyerNote, now);
        order.channel = Channel.B2C; order.consumerUserId = consumerUserId; order.paymentMethod = method;
        order.shippingAddress = address;
        order.termsVersion = termsVersion; order.termsAcceptedAt = now;
        return order;
    }
    public void move(OrderStatus target, Instant now) {
        status = target; updatedAt = now;
        if (target == OrderStatus.DELIVERED) deliveredAt = now;
    }
    public void shipment(String carrier, String trackingNumber) { this.carrier = carrier; this.trackingNumber = trackingNumber; }
    public void payment(PaymentStatus value, Instant now) { paymentStatus = value; updatedAt = now; }
    public UUID id() { return id; }
    public UUID checkoutId() { return checkoutId; }
    public Channel channel() { return channel; }
    public UUID buyerCompanyId() { return buyerCompanyId; }
    public UUID consumerUserId() { return consumerUserId; }
    public UUID sellerCompanyId() { return sellerCompanyId; }
    public OrderStatus status() { return status; }
    public PaymentStatus paymentStatus() { return paymentStatus; }
    public PaymentMethod paymentMethod() { return paymentMethod; }
    public String currency() { return currency; }
    public BigDecimal totalAmount() { return totalAmount; }
    public BigDecimal subtotalAmount() { return subtotalAmount; }
    public BigDecimal shippingAmount() { return shippingAmount; }
    /** Goods plus shipping make the total (ADR-011). */
    public void charges(BigDecimal subtotal, BigDecimal shipping) { subtotalAmount = subtotal; shippingAmount = shipping; totalAmount = subtotal.add(shipping); }
    public String buyerNote() { return buyerNote; }
    public String carrier() { return carrier; }
    public String trackingNumber() { return trackingNumber; }
    public ShippingAddress shippingAddress() { return shippingAddress; }
    public String termsVersion() { return termsVersion; }
    public Instant termsAcceptedAt() { return termsAcceptedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}

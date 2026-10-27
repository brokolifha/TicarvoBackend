package com.ticarvo.order.dto;

import com.ticarvo.order.model.Channel;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.model.PaymentMethod;
import com.ticarvo.order.model.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {}

    // Requests. expectedUnitPrice is what the buyer saw; a different catalog price means 409 PRICE_CHANGED (ADR-009).
    public record LineInput(@NotNull UUID productId, @NotNull @Min(1) @Max(100000) Integer quantity,
        @NotNull @Pattern(regexp = "[0-9]{1,15}(\\.[0-9]{1,2})?") String expectedUnitPrice) {}
    public record SellerOrderInput(@NotNull UUID sellerCompanyId, @Size(max = 1000) String note,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineInput> lines) {}
    public record CheckoutInput(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid SellerOrderInput> orders) {}
    public record Address(@NotBlank @Size(max = 100) String recipient, @NotBlank @Pattern(regexp = "\\+?[0-9 ]{10,20}") String phone,
        @NotBlank @Size(max = 300) String address, @NotBlank @Size(max = 100) String district, @NotBlank @Size(max = 100) String city,
        @Pattern(regexp = "[0-9]{5}") String postalCode) {}
    /** Both consents must be true: the pre-information comes right before the payment obligation (ADR-010). */
    public record ConsumerCheckoutInput(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid SellerOrderInput> orders,
        @NotNull @Valid Address shippingAddress, @NotNull PaymentMethod paymentMethod,
        @NotNull @AssertTrue Boolean acceptedPreInformation, @NotNull @AssertTrue Boolean acceptedDistanceSalesContract) {}
    public record Transition(@NotNull @PositiveOrZero Long expectedVersion, @Size(max = 1000) String note) {}
    public record Reason(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(min = 5, max = 1000) String note) {}
    public record Ship(@NotNull @PositiveOrZero Long expectedVersion, @Size(max = 100) String carrier, @Size(max = 100) String trackingNumber) {}
    public record Payment(@NotNull @PositiveOrZero Long expectedVersion, @NotNull PaymentStatus status) {}

    // Responses. Amounts are strings with two decimals, like catalog prices.
    public record Line(int lineNo, UUID productId, String sku, String name, String unitAmount, boolean taxIncluded, int quantity, String lineAmount,
        int vatRate, String unit) {}
    /** B2C orders have no buyer company; buyerName is then the recipient. paymentMethod and shippingAddress are B2C only. */
    public record Order(UUID id, UUID checkoutId, Channel channel, UUID buyerCompanyId, String buyerName, UUID sellerCompanyId, String sellerName,
        OrderStatus status, PaymentStatus paymentStatus, PaymentMethod paymentMethod, String currency, String totalAmount, String buyerNote,
        Address shippingAddress, String carrier, String trackingNumber, Instant deliveredAt, Instant createdAt, Instant updatedAt, long version,
        List<Line> lines) {}
    public record Event(String action, OrderStatus fromStatus, OrderStatus toStatus, String note, Instant createdAt) {}
    public record Detail(Order order, List<Event> events) {}
    public record CheckoutResult(UUID checkoutId, List<Order> orders) {}
    public record Page(List<Order> content, int page, int size, long totalElements) {}
}

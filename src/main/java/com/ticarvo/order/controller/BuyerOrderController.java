package com.ticarvo.order.controller;
import com.ticarvo.order.dto.OrderDtos;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.service.CheckoutService;
import com.ticarvo.order.service.OrderQueries;
import com.ticarvo.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** The buyer side of ADR-009. */
@RestController @RequestMapping("/api/v1/companies/{buyerId}/orders")
public class BuyerOrderController {
    private final CheckoutService checkout;
    private final OrderQueries queries;
    private final OrderService orders;
    public BuyerOrderController(CheckoutService checkout, OrderQueries queries, OrderService orders) {
        this.checkout = checkout; this.queries = queries; this.orders = orders;
    }
    /** 201 for a new checkout, 200 when the same Idempotency-Key replays it. */
    @PostMapping
    public ResponseEntity<OrderDtos.CheckoutResult> place(@PathVariable UUID buyerId, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody OrderDtos.CheckoutInput body, Authentication auth) {
        var result = checkout.checkout(buyerId, key, body, auth);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
    @GetMapping
    public OrderDtos.Page list(@PathVariable UUID buyerId, @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return queries.list(buyerId, false, status, page, size, auth);
    }
    @GetMapping("/{orderId}")
    public OrderDtos.Detail get(@PathVariable UUID buyerId, @PathVariable UUID orderId, Authentication auth) { return queries.get(buyerId, false, orderId, auth); }
    @PostMapping("/{orderId}/cancel")
    public OrderDtos.Detail cancel(@PathVariable UUID buyerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.buyerCancel(buyerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/confirm-delivery")
    public OrderDtos.Detail confirmDelivery(@PathVariable UUID buyerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.buyerConfirmDelivery(buyerId, orderId, body, auth);
    }
}

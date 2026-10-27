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

/** The consumer side of ADR-010: a person's own retail orders. */
@RestController @RequestMapping("/api/v1/me/orders")
public class ConsumerOrderController {
    private final CheckoutService checkout;
    private final OrderQueries queries;
    private final OrderService orders;
    public ConsumerOrderController(CheckoutService checkout, OrderQueries queries, OrderService orders) {
        this.checkout = checkout; this.queries = queries; this.orders = orders;
    }
    /** 201 for a new checkout, 200 when the same Idempotency-Key replays it. */
    @PostMapping
    public ResponseEntity<OrderDtos.CheckoutResult> place(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody OrderDtos.ConsumerCheckoutInput body, Authentication auth) {
        var result = checkout.consumerCheckout(key, body, auth);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
    @GetMapping
    public OrderDtos.Page list(@RequestParam(required = false) OrderStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return queries.mine(status, page, size, auth);
    }
    @GetMapping("/{orderId}")
    public OrderDtos.Detail get(@PathVariable UUID orderId, Authentication auth) { return queries.mine(orderId, auth); }
    @PostMapping("/{orderId}/cancel")
    public OrderDtos.Detail cancel(@PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.consumerCancel(orderId, body, auth);
    }
    @PostMapping("/{orderId}/confirm-delivery")
    public OrderDtos.Detail confirmDelivery(@PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.consumerConfirmDelivery(orderId, body, auth);
    }
    @PostMapping("/{orderId}/return-request")
    public OrderDtos.Detail returnRequest(@PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Reason body, Authentication auth) {
        return orders.returnRequest(orderId, body, auth);
    }
}

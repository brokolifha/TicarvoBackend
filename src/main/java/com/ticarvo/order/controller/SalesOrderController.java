package com.ticarvo.order.controller;
import com.ticarvo.order.dto.OrderDtos;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.service.OrderQueries;
import com.ticarvo.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** The seller side of ADR-009: incoming orders and their processing. */
@RestController @RequestMapping("/api/v1/companies/{sellerId}/sales")
public class SalesOrderController {
    private final OrderQueries queries;
    private final OrderService orders;
    public SalesOrderController(OrderQueries queries, OrderService orders) { this.queries = queries; this.orders = orders; }
    @GetMapping
    public OrderDtos.Page list(@PathVariable UUID sellerId, @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return queries.list(sellerId, true, status, page, size, auth);
    }
    @GetMapping("/{orderId}")
    public OrderDtos.Detail get(@PathVariable UUID sellerId, @PathVariable UUID orderId, Authentication auth) { return queries.get(sellerId, true, orderId, auth); }
    @PostMapping("/{orderId}/accept")
    public OrderDtos.Detail accept(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.accept(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/reject")
    public OrderDtos.Detail reject(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Reason body, Authentication auth) {
        return orders.reject(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/cancel")
    public OrderDtos.Detail cancel(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Reason body, Authentication auth) {
        return orders.sellerCancel(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/ship")
    public OrderDtos.Detail ship(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Ship body, Authentication auth) {
        return orders.ship(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/deliver")
    public OrderDtos.Detail deliver(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.deliver(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/confirm-return")
    public OrderDtos.Detail confirmReturn(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Transition body, Authentication auth) {
        return orders.confirmReturn(sellerId, orderId, body, auth);
    }
    @PostMapping("/{orderId}/payment")
    public OrderDtos.Detail payment(@PathVariable UUID sellerId, @PathVariable UUID orderId, @Valid @RequestBody OrderDtos.Payment body, Authentication auth) {
        return orders.payment(sellerId, orderId, body, auth);
    }
}

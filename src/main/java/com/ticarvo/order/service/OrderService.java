package com.ticarvo.order.service;

import com.ticarvo.catalog.service.CatalogOrdering;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.order.dto.OrderDtos;
import com.ticarvo.order.entity.OrderEvent;
import com.ticarvo.order.entity.PurchaseOrder;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.model.PaymentStatus;
import com.ticarvo.order.repository.OrderEventRepository;
import com.ticarvo.order.repository.PurchaseOrderRepository;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import static com.ticarvo.order.model.OrderStatus.*;

/** The order state machine (ADR-009, ADR-010): who may move an order from which state, with history and stock release. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OrderService {
    /** Mesafeli Sözleşmeler Yönetmeliği: 14 days from delivery. */
    static final Duration RETURN_WINDOW = Duration.ofDays(14);
    /** Who acts: a buyer or seller company member, or the consumer who placed the order. */
    private record Party(UUID company, boolean asSeller, boolean consumer) {
        static Party buyer(UUID company) { return new Party(company, false, false); }
        static Party seller(UUID company) { return new Party(company, true, false); }
        static Party person() { return new Party(null, false, true); }
    }
    private final PurchaseOrderRepository orders;
    private final OrderEventRepository events;
    private final OrderQueries queries;
    private final CompanyAccess companies;
    private final CurrentUserProvider users;
    private final CatalogOrdering catalog;
    private final Clock clock;
    public OrderService(PurchaseOrderRepository orders, OrderEventRepository events, OrderQueries queries, CompanyAccess companies,
            CurrentUserProvider users, CatalogOrdering catalog, Clock clock) {
        this.orders = orders; this.events = events; this.queries = queries; this.companies = companies; this.users = users;
        this.catalog = catalog; this.clock = clock;
    }

    public OrderDtos.Detail buyerCancel(UUID buyer, UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.buyer(buyer), id, r.expectedVersion(), auth, "BUYER_CANCELLED", Set.of(PLACED), CANCELLED, r.note(), this::release);
    }
    public OrderDtos.Detail buyerConfirmDelivery(UUID buyer, UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.buyer(buyer), id, r.expectedVersion(), auth, "DELIVERY_CONFIRMED", Set.of(SHIPPED), DELIVERED, r.note(), o -> {});
    }
    /** A consumer may withdraw before shipping without a reason (ADR-010). */
    public OrderDtos.Detail consumerCancel(UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.person(), id, r.expectedVersion(), auth, "CONSUMER_CANCELLED", Set.of(PLACED, ACCEPTED), CANCELLED, r.note(), this::release);
    }
    public OrderDtos.Detail consumerConfirmDelivery(UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.person(), id, r.expectedVersion(), auth, "DELIVERY_CONFIRMED", Set.of(SHIPPED), DELIVERED, r.note(), o -> {});
    }
    /** Withdrawal after shipping: while in transit, or within 14 days of delivery. */
    public OrderDtos.Detail returnRequest(UUID id, OrderDtos.Reason r, Authentication auth) {
        return move(Party.person(), id, r.expectedVersion(), auth, "RETURN_REQUESTED", Set.of(SHIPPED, DELIVERED), RETURN_REQUESTED, r.note(), o -> {
            if (o.status() == DELIVERED && clock.instant().isAfter(o.deliveredAt().plus(RETURN_WINDOW))) {
                throw new ApiException(HttpStatus.CONFLICT, "RETURN_WINDOW_CLOSED", "The 14-day withdrawal period has ended.");
            }
        });
    }
    public OrderDtos.Detail accept(UUID seller, UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "ACCEPTED", Set.of(PLACED), ACCEPTED, r.note(), o -> {});
    }
    public OrderDtos.Detail reject(UUID seller, UUID id, OrderDtos.Reason r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "REJECTED", Set.of(PLACED), REJECTED, r.note(), this::release);
    }
    public OrderDtos.Detail sellerCancel(UUID seller, UUID id, OrderDtos.Reason r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "SELLER_CANCELLED", Set.of(ACCEPTED), CANCELLED, r.note(), this::release);
    }
    public OrderDtos.Detail ship(UUID seller, UUID id, OrderDtos.Ship r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "SHIPPED", Set.of(ACCEPTED), SHIPPED, null,
            o -> o.shipment(blankToNull(r.carrier()), blankToNull(r.trackingNumber())));
    }
    public OrderDtos.Detail deliver(UUID seller, UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "DELIVERED", Set.of(SHIPPED), DELIVERED, r.note(), o -> {});
    }
    /** The returned goods arrived back; their stock is released. The refund is recorded with payment(REFUNDED). */
    public OrderDtos.Detail confirmReturn(UUID seller, UUID id, OrderDtos.Transition r, Authentication auth) {
        return move(Party.seller(seller), id, r.expectedVersion(), auth, "RETURN_CONFIRMED", Set.of(RETURN_REQUESTED), RETURNED, r.note(), this::release);
    }
    /** Which payment state may be recorded in which order state; an unchanged value writes nothing. */
    private static final Map<PaymentStatus, Set<OrderStatus>> PAYMENT_STATES = Map.of(
        PaymentStatus.PAID, Set.of(ACCEPTED, SHIPPED, DELIVERED, RETURN_REQUESTED),
        PaymentStatus.UNPAID, Set.of(ACCEPTED, SHIPPED, DELIVERED),
        PaymentStatus.REFUNDED, Set.of(CANCELLED, RETURNED));
    public OrderDtos.Detail payment(UUID seller, UUID id, OrderDtos.Payment r, Authentication auth) {
        UUID actor = actor(Party.seller(seller), auth);
        var order = locked(Party.seller(seller), id, r.expectedVersion(), auth);
        boolean refundWithoutPayment = r.status() == PaymentStatus.REFUNDED && order.paymentStatus() != PaymentStatus.PAID;
        if (!PAYMENT_STATES.get(r.status()).contains(order.status()) || refundWithoutPayment) throw stateConflict();
        if (order.paymentStatus() != r.status()) {
            var now = clock.instant();
            order.payment(r.status(), now);
            String action = switch (r.status()) { case PAID -> "PAYMENT_RECORDED"; case UNPAID -> "PAYMENT_REVERTED"; case REFUNDED -> "PAYMENT_REFUNDED"; };
            events.save(new OrderEvent(id, actor, action, order.status(), order.status(), null, now));
            orders.saveAndFlush(order);
        }
        return queries.detail(order, true);
    }

    private OrderDtos.Detail move(Party party, UUID id, long expectedVersion, Authentication auth, String action,
            Set<OrderStatus> from, OrderStatus to, String note, Consumer<PurchaseOrder> effect) {
        UUID actor = actor(party, auth);
        var order = locked(party, id, expectedVersion, auth);
        if (!from.contains(order.status())) throw stateConflict();
        var previous = order.status();
        var now = clock.instant();
        effect.accept(order);
        order.move(to, now);
        events.save(new OrderEvent(id, actor, action, previous, to, blankToNull(note), now));
        orders.saveAndFlush(order);
        return queries.detail(order, party.asSeller());
    }
    private UUID actor(Party party, Authentication auth) {
        return party.consumer() ? users.currentUserId(auth) : companies.requireMember(party.company(), auth);
    }
    /** Seller row first, then the order row: the same lock order as checkout and every catalog change. */
    private PurchaseOrder locked(Party party, UUID id, long expectedVersion, Authentication auth) {
        UUID seller = (party.consumer() ? orders.sellerOfConsumerOrder(id, users.currentUserId(auth))
            : orders.sellerOf(id, party.company(), party.asSeller())).orElseThrow(OrderQueries::notFound);
        companies.lockSeller(seller);
        if (!party.consumer()) companies.requireMember(party.company(), auth);
        var order = orders.lockById(id).orElseThrow(OrderQueries::notFound);
        if (order.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_VERSION_CONFLICT", "The order changed. Reload before retrying.");
        }
        return order;
    }
    private void release(PurchaseOrder order) { catalog.release(order.sellerCompanyId(), queries.quantities(order.id())); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static ApiException stateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT", "The order is not in a state that allows this.");
    }
}

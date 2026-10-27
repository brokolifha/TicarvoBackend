package com.ticarvo.order.service;

import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.order.dto.OrderDtos;
import com.ticarvo.order.entity.OrderEvent;
import com.ticarvo.order.entity.OrderLine;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.order.entity.PurchaseOrder;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.repository.OrderEventRepository;
import com.ticarvo.order.repository.OrderLineRepository;
import com.ticarvo.order.repository.PurchaseOrderRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.validation.PageValidation;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Order reads for both sides (ADR-009): only the buyer and seller companies see an order. */
@Service
@Transactional(readOnly = true)
public class OrderQueries {
    private final PurchaseOrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderEventRepository events;
    private final CompanyAccess companies;
    private final CurrentUserProvider users;
    public OrderQueries(PurchaseOrderRepository orders, OrderLineRepository lines, OrderEventRepository events, CompanyAccess companies,
            CurrentUserProvider users) {
        this.orders = orders; this.lines = lines; this.events = events; this.companies = companies; this.users = users;
    }
    /** The caller's own consumer orders (ADR-010); company members never see them. */
    public OrderDtos.Page mine(OrderStatus status, int page, int size, Authentication auth) {
        PageValidation.validatePage(page, size);
        var result = orders.consumerList(users.currentUserId(auth), status, PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new OrderDtos.Page(views(result.getContent()), page, size, result.getTotalElements());
    }
    public OrderDtos.Detail mine(UUID id, Authentication auth) {
        UUID user = users.currentUserId(auth);
        return detail(orders.findById(id).filter(o -> user.equals(o.consumerUserId())).orElseThrow(OrderQueries::notFound));
    }
    public OrderDtos.Page list(UUID company, boolean asSeller, OrderStatus status, int page, int size, Authentication auth) {
        companies.requireMember(company, auth);
        PageValidation.validatePage(page, size);
        var request = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id")));
        var result = asSeller ? orders.sellerList(company, status, request) : orders.buyerList(company, status, request);
        return new OrderDtos.Page(views(result.getContent()), page, size, result.getTotalElements());
    }
    public OrderDtos.Detail get(UUID company, boolean asSeller, UUID id, Authentication auth) {
        companies.requireMember(company, auth);
        return detail(orders.findById(id).filter(o -> party(o, company, asSeller)).orElseThrow(OrderQueries::notFound));
    }
    OrderDtos.Detail detail(PurchaseOrder order) {
        return new OrderDtos.Detail(views(List.of(order)).getFirst(),
            events.findByOrderIdOrderByCreatedAtAsc(order.id()).stream().map(OrderQueries::event).toList());
    }
    OrderDtos.CheckoutResult checkout(UUID checkoutId) {
        return new OrderDtos.CheckoutResult(checkoutId, views(orders.findByCheckoutIdOrderBySellerCompanyId(checkoutId)));
    }
    Map<UUID, Integer> quantities(UUID orderId) {
        return lines.findByOrderIdInOrderByOrderIdAscLineNoAsc(List.of(orderId)).stream()
            .collect(Collectors.toMap(OrderLine::productId, OrderLine::quantity));
    }
    static boolean party(PurchaseOrder order, UUID company, boolean asSeller) {
        return company.equals(asSeller ? order.sellerCompanyId() : order.buyerCompanyId());
    }
    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found."); }
    static String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }

    private List<OrderDtos.Order> views(List<PurchaseOrder> page) {
        if (page.isEmpty()) return List.of();
        var byOrder = lines.findByOrderIdInOrderByOrderIdAscLineNoAsc(page.stream().map(PurchaseOrder::id).toList()).stream()
            .collect(Collectors.groupingBy(OrderLine::orderId));
        var ids = new HashSet<UUID>();
        page.forEach(o -> { if (o.buyerCompanyId() != null) ids.add(o.buyerCompanyId()); ids.add(o.sellerCompanyId()); });
        var names = companies.summaries(ids);
        return page.stream().map(o -> {
            var a = o.shippingAddress();
            var address = a == null ? null : new OrderDtos.Address(a.recipient(), a.phone(), a.address(), a.district(), a.city(), a.postalCode());
            String buyerName = o.buyerCompanyId() != null ? names.get(o.buyerCompanyId()).name() : a == null ? null : a.recipient();
            return new OrderDtos.Order(o.id(), o.checkoutId(), o.channel(), o.buyerCompanyId(), buyerName,
                o.sellerCompanyId(), names.get(o.sellerCompanyId()).name(), o.status(), o.paymentStatus(), o.paymentMethod(), o.currency(),
                money(o.totalAmount()), o.buyerNote(), address, o.carrier(), o.trackingNumber(), o.deliveredAt(), o.createdAt(), o.updatedAt(),
                o.version(), byOrder.getOrDefault(o.id(), List.of()).stream().map(OrderQueries::line).toList());
        }).toList();
    }
    private static OrderDtos.Line line(OrderLine l) {
        return new OrderDtos.Line(l.lineNo(), l.productId(), l.sku(), l.name(), money(l.unitAmount()), l.taxIncluded(), l.quantity(), money(l.lineAmount()));
    }
    private static OrderDtos.Event event(OrderEvent e) {
        return new OrderDtos.Event(e.action(), e.fromStatus(), e.toStatus(), e.note(), e.createdAt());
    }
}

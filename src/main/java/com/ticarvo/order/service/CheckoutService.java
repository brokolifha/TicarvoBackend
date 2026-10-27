package com.ticarvo.order.service;

import com.ticarvo.catalog.dto.OrderableProduct;
import com.ticarvo.catalog.service.CatalogOrdering;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.identity.service.UserDirectory;
import com.ticarvo.order.dto.OrderDtos;
import com.ticarvo.order.entity.Checkout;
import com.ticarvo.order.entity.OrderEvent;
import com.ticarvo.order.entity.OrderLine;
import com.ticarvo.order.entity.PurchaseOrder;
import com.ticarvo.order.entity.ShippingAddress;
import com.ticarvo.order.model.OrderStatus;
import com.ticarvo.order.repository.CheckoutRepository;
import com.ticarvo.order.repository.OrderEventRepository;
import com.ticarvo.order.repository.OrderLineRepository;
import com.ticarvo.order.repository.PurchaseOrderRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.trade.service.TradeAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

/** Places one order per seller from a single request, all or nothing; B2B (ADR-009) and B2C (ADR-010) share this path. */
@Service
public class CheckoutService {
    public record Result(boolean created, OrderDtos.CheckoutResult body) {}
    /** Builds one seller order of the checkout; the channel decides who the buyer is. */
    interface OrderFactory { PurchaseOrder create(UUID checkoutId, UUID seller, String currency, BigDecimal total, String note, Instant now); }
    static final int MAX_LINES = 100;
    private final CheckoutRepository checkouts;
    private final PurchaseOrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderEventRepository events;
    private final OrderQueries queries;
    private final CompanyAccess companies;
    private final TradeAccess trade;
    private final CatalogOrdering catalog;
    private final CurrentUserProvider users;
    private final UserDirectory directory;
    private final PrincipalRateLimit limits;
    private final Clock clock;
    private final String termsVersion;
    public CheckoutService(CheckoutRepository checkouts, PurchaseOrderRepository orders, OrderLineRepository lines, OrderEventRepository events,
            OrderQueries queries, CompanyAccess companies, TradeAccess trade, CatalogOrdering catalog, CurrentUserProvider users,
            UserDirectory directory, PrincipalRateLimit limits, Clock clock, @Value("${ticarvo.legal.terms-version:draft-1}") String termsVersion) {
        this.checkouts = checkouts; this.orders = orders; this.lines = lines; this.events = events; this.queries = queries;
        this.companies = companies; this.trade = trade; this.catalog = catalog; this.users = users; this.directory = directory;
        this.limits = limits; this.clock = clock; this.termsVersion = termsVersion;
    }

    /** B2B: a buyer company orders granted products at its B2B price. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result checkout(UUID buyer, String idempotencyKey, OrderDtos.CheckoutInput input, Authentication auth) {
        key(idempotencyKey);
        UUID actor = companies.requireMember(buyer, auth);
        var sellers = sellers(input.orders());
        if (sellers.contains(buyer)) throw invalid("A company cannot order from itself.");
        // Buyer and sellers in UUID order (no deadlock between two companies ordering from each other), then the role again.
        var all = new ArrayList<>(sellers); all.add(buyer);
        var locked = companies.lockCompanies(all);
        companies.requireMember(buyer, auth);
        // Under the buyer lock, so two requests with the same key cannot both pass this check.
        String hash = hash(input.orders(), "");
        var previous = checkouts.findByBuyerCompanyIdAndIdempotencyKey(buyer, idempotencyKey);
        if (previous.isPresent()) return replay(previous.get(), hash);
        limits.check(PrincipalRateLimit.Operation.ORDER_CREATE, actor);
        return place(input.orders(), actor, new Checkout(buyer, null, idempotencyKey, hash, actor, clock.instant()),
            seller -> { var s = locked.get(seller); return s != null && !s.suspended() && trade.hasApprovedRelationship(buyer, seller); },
            (seller, ids) -> catalog.orderable(seller, buyer, ids),
            (checkoutId, seller, currency, total, note, now) -> new PurchaseOrder(checkoutId, buyer, seller, currency, total, note, now));
    }

    /** B2C: a person orders public products at the tax-inclusive retail price, after accepting the pre-information and contract. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result consumerCheckout(String idempotencyKey, OrderDtos.ConsumerCheckoutInput input, Authentication auth) {
        key(idempotencyKey);
        UUID actor = users.currentUserId(auth);
        var sellers = sellers(input.orders());
        // The person first, then the sellers: serializes this person's retries without touching any company lock order.
        directory.lock(actor);
        var locked = companies.lockCompanies(sellers);
        var a = input.shippingAddress();
        var address = new ShippingAddress(a.recipient().strip(), a.phone().strip(), a.address().strip(), a.district().strip(), a.city().strip(),
            a.postalCode() == null || a.postalCode().isBlank() ? null : a.postalCode().strip());
        String hash = hash(input.orders(), input.paymentMethod() + "|" + address);
        var previous = checkouts.findByConsumerUserIdAndIdempotencyKey(actor, idempotencyKey);
        if (previous.isPresent()) return replay(previous.get(), hash);
        limits.check(PrincipalRateLimit.Operation.ORDER_CREATE, actor);
        return place(input.orders(), actor, new Checkout(null, actor, idempotencyKey, hash, actor, clock.instant()),
            seller -> { var s = locked.get(seller); return s != null && !s.suspended(); },
            catalog::retailOrderable,
            (checkoutId, seller, currency, total, note, now) ->
                PurchaseOrder.consumer(checkoutId, actor, seller, currency, total, note, input.paymentMethod(), address, termsVersion, now));
    }

    /** Every check first, then any write, so a failing seller leaves nothing behind. */
    private Result place(List<OrderDtos.SellerOrderInput> groups, UUID actor, Checkout draft, java.util.function.Predicate<UUID> sellerAvailable,
            BiFunction<UUID, List<UUID>, Map<UUID, OrderableProduct>> lookup, OrderFactory factory) {
        var products = new HashMap<UUID, Map<UUID, OrderableProduct>>();
        boolean priceChanged = false;
        for (var group : groups) {
            if (!sellerAvailable.test(group.sellerCompanyId())) throw productNotFound();
            var ids = group.lines().stream().map(OrderDtos.LineInput::productId).toList();
            var found = lookup.apply(group.sellerCompanyId(), ids);
            if (found.size() != ids.size()) throw productNotFound();
            if (found.values().stream().map(OrderableProduct::currency).distinct().count() > 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MIXED_CURRENCY", "All lines of one seller must share a currency.");
            }
            for (var line : group.lines()) {
                var p = found.get(line.productId());
                // Case pack and minimum are wholesale rules; consumer lookups report 1 and 1 (ADR-011).
                if (line.quantity() < p.minQuantity() || line.quantity() % p.casePack() != 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "QUANTITY_NOT_ALLOWED", "Product " + p.id()
                        + " sells in multiples of " + p.casePack() + ", at least " + p.minQuantity() + ".");
                }
                priceChanged |= new BigDecimal(line.expectedUnitPrice()).compareTo(found.get(line.productId()).amount()) != 0;
            }
            products.put(group.sellerCompanyId(), found);
        }
        if (priceChanged) throw new ApiException(HttpStatus.CONFLICT, "PRICE_CHANGED", "A price changed. Reload the products before ordering.");

        var now = clock.instant();
        var checkout = checkouts.save(draft);
        for (var group : groups) {
            var found = products.get(group.sellerCompanyId());
            var quantities = new HashMap<UUID, Integer>();
            group.lines().forEach(line -> quantities.put(line.productId(), line.quantity()));
            var lacking = catalog.reserve(group.sellerCompanyId(), quantities);
            if (!lacking.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Not enough stock for products: " + lacking + ".");
            }
            var amounts = group.lines().stream()
                .map(line -> found.get(line.productId()).amount().multiply(BigDecimal.valueOf(line.quantity())).setScale(2)).toList();
            var total = amounts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            // DECIMAL(19,2) holds 17 integer digits.
            if (total.precision() - total.scale() > 17) throw invalid("Order total is too large.");
            var order = orders.save(factory.create(checkout.id(), group.sellerCompanyId(), currency(found), total, blankToNull(group.note()), now));
            for (int i = 0; i < group.lines().size(); i++) {
                var line = group.lines().get(i);
                var product = found.get(line.productId());
                lines.save(new OrderLine(order.id(), i + 1, product.id(), product.sku(), product.name(), product.amount().setScale(2),
                    product.taxIncluded(), line.quantity(), amounts.get(i), product.vatRate(), product.unit()));
            }
            events.save(new OrderEvent(order.id(), actor, "PLACED", null, OrderStatus.PLACED, null, now));
        }
        orders.flush();
        return new Result(true, queries.checkout(checkout.id()));
    }
    private Result replay(Checkout previous, String hash) {
        if (!previous.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request.");
        }
        return new Result(false, queries.checkout(previous.id()));
    }
    private static List<UUID> sellers(List<OrderDtos.SellerOrderInput> groups) {
        var sellers = groups.stream().map(OrderDtos.SellerOrderInput::sellerCompanyId).toList();
        var productIds = groups.stream().flatMap(o -> o.lines().stream()).map(OrderDtos.LineInput::productId).toList();
        if (new HashSet<>(sellers).size() != sellers.size()) throw invalid("Each seller may appear once.");
        if (new HashSet<>(productIds).size() != productIds.size()) throw invalid("Each product may appear once.");
        if (productIds.size() > MAX_LINES) throw invalid("At most " + MAX_LINES + " lines per request.");
        return sellers;
    }
    private static void key(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw invalid("Idempotency-Key header must be 8..64 characters of A-Z, a-z, 0-9, _ or -.");
        }
    }
    private static String currency(Map<UUID, OrderableProduct> found) { return found.values().iterator().next().currency(); }
    /** Canonical form: sellers and lines sorted, so reordering the same request is still the same request. */
    static String hash(List<OrderDtos.SellerOrderInput> groups, String extra) {
        var text = new StringBuilder(extra).append('\n');
        groups.stream().sorted(Comparator.comparing(OrderDtos.SellerOrderInput::sellerCompanyId)).forEach(group -> {
            text.append(group.sellerCompanyId()).append('|').append(Objects.toString(blankToNull(group.note()), "")).append('\n');
            group.lines().stream().sorted(Comparator.comparing(OrderDtos.LineInput::productId)).forEach(line -> text.append(line.productId())
                .append(':').append(line.quantity()).append(':').append(new BigDecimal(line.expectedUnitPrice()).stripTrailingZeros().toPlainString()).append('\n'));
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static ApiException invalid(String detail) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail); }
    private static ApiException productNotFound() {
        // One answer for every reason, so a private product's existence never leaks.
        return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "A product is not available to this buyer.");
    }
}

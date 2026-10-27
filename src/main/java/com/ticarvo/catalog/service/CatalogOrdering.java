package com.ticarvo.catalog.service;

import com.ticarvo.catalog.dto.OrderableProduct;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * Internal catalog API for orders (ADR-009, closes ARCH-M3-02). Every method joins the caller's transaction,
 * which already holds the seller lock; the REQUIRES_NEW entry services must never be called from there.
 */
public interface CatalogOrdering {
    /** Products the buyer may order now (published, granted, seller not suspended); ids it may not see are absent. */
    Map<UUID, OrderableProduct> orderable(UUID sellerId, UUID buyerId, Collection<UUID> productIds);
    /** Products a consumer may buy (public, published, retail price set, seller not suspended), at the retail price with taxes included (ADR-010). */
    Map<UUID, OrderableProduct> retailOrderable(UUID sellerId, Collection<UUID> productIds);
    /** Takes tracked stock all-or-nothing; returns the ids that lack stock, in which case nothing was taken. */
    List<UUID> reserve(UUID sellerId, Map<UUID, Integer> quantities);
    /** Gives reserved stock back; products that stopped tracking stock are skipped. */
    void release(UUID sellerId, Map<UUID, Integer> quantities);
}

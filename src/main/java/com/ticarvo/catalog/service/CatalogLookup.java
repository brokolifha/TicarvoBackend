package com.ticarvo.catalog.service;

import java.util.Optional;
import java.util.UUID;
/** Catalog boundary for the admin module: which company sells a product (ADR-008). */
public interface CatalogLookup {
    Optional<UUID> sellerOf(UUID productId);
}

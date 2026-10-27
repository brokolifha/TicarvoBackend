package com.ticarvo.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;
/** What an order copies from the catalog (ADR-009); stockQuantity is null when stock is not tracked. */
public record OrderableProduct(UUID id, String sku, String name, BigDecimal amount, String currency, boolean taxIncluded, Integer stockQuantity,
    int vatRate, String unit, int casePack, int minQuantity) {}

package com.ticarvo.catalog.service;

import com.ticarvo.catalog.dto.OrderableProduct;
import com.ticarvo.catalog.entity.Product;
import com.ticarvo.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CatalogOrderingService implements CatalogOrdering {
    private final ProductRepository products;
    public CatalogOrderingService(ProductRepository products) { this.products = products; }

    public Map<UUID, OrderableProduct> orderable(UUID sellerId, UUID buyerId, Collection<UUID> productIds) {
        return products.buyerOrderable(productIds, sellerId, buyerId).stream().collect(Collectors.toMap(Product::id,
            p -> new OrderableProduct(p.id(), p.sku(), p.name(), p.amount(), p.currency(), p.taxIncluded(), p.stockQuantity(),
            p.vatRate(), p.unit().name(), p.casePack(), p.minQuantity())));
    }
    public Map<UUID, OrderableProduct> retailOrderable(UUID sellerId, Collection<UUID> productIds) {
        return products.retailOrderable(productIds, sellerId).stream().collect(Collectors.toMap(Product::id,
            p -> new OrderableProduct(p.id(), p.sku(), p.name(), p.retailAmount(), p.retailCurrency(), true, p.stockQuantity(),
            p.vatRate(), p.unit().name(), 1, 1)));
    }
    public List<UUID> reserve(UUID sellerId, Map<UUID, Integer> quantities) {
        var tracked = owned(sellerId, quantities.keySet()).stream().filter(p -> p.stockQuantity() != null).toList();
        var lacking = tracked.stream().filter(p -> p.stockQuantity() < quantities.get(p.id())).map(Product::id).sorted().toList();
        if (lacking.isEmpty()) tracked.forEach(p -> p.stock(p.stockQuantity() - quantities.get(p.id())));
        return lacking;
    }
    public void release(UUID sellerId, Map<UUID, Integer> quantities) {
        owned(sellerId, quantities.keySet()).stream().filter(p -> p.stockQuantity() != null)
            .forEach(p -> p.stock(p.stockQuantity() + quantities.get(p.id())));
    }
    private List<Product> owned(UUID sellerId, Collection<UUID> ids) {
        return products.findAllById(ids).stream().filter(p -> p.seller().equals(sellerId)).toList();
    }
}

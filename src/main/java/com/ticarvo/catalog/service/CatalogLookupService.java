package com.ticarvo.catalog.service;

import com.ticarvo.catalog.entity.Product;
import com.ticarvo.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
public class CatalogLookupService implements CatalogLookup {
    private final ProductRepository products;
    public CatalogLookupService(ProductRepository products) { this.products = products; }
    @Transactional(readOnly = true)
    public Optional<UUID> sellerOf(UUID productId) { return products.findById(productId).map(Product::seller); }
}

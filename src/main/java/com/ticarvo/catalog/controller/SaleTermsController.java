package com.ticarvo.catalog.controller;

import com.ticarvo.catalog.service.CatalogService;
import com.ticarvo.catalog.service.PublicCatalogService;
import com.ticarvo.company.dto.SaleTerms;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** ADR-012: a seller's terms for consumers (anonymous) and for its approved dealers. */
@RestController
@RequestMapping("/api/v1")
public class SaleTermsController {
    private final PublicCatalogService publicCatalog;
    private final CatalogService catalog;
    public SaleTermsController(PublicCatalogService publicCatalog,CatalogService catalog) { this.publicCatalog=publicCatalog; this.catalog=catalog; }
    @GetMapping("/catalog/sellers/{sellerId}/terms")
    public SaleTerms consumer(@PathVariable UUID sellerId) { return publicCatalog.terms(sellerId); }
    @GetMapping("/companies/{buyerId}/catalog/sellers/{sellerId}/terms")
    public SaleTerms dealer(@PathVariable UUID buyerId,@PathVariable UUID sellerId,Authentication auth) { return catalog.dealerTerms(buyerId,sellerId,auth); }
}

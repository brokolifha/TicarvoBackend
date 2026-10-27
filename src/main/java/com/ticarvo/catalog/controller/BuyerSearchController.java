package com.ticarvo.catalog.controller;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.service.CatalogService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** C-09: the buyer searches the B2B catalogs of all its approved sellers at once. */
@RestController
@RequestMapping("/api/v1/companies/{buyerId}/catalog/products")
public class BuyerSearchController {
    private final CatalogService catalog;
    public BuyerSearchController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping
    public CatalogDtos.Page<CatalogDtos.BuyerSearchItem> search(@PathVariable UUID buyerId,@RequestParam(required=false) String q,
            @RequestParam(required=false) UUID categoryId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            Authentication auth) {
        return catalog.buyerSearch(buyerId,q,categoryId,page,size,auth);
    }
}

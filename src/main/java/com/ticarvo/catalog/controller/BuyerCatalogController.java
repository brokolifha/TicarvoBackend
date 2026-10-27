package com.ticarvo.catalog.controller;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/companies/{buyerId}/catalog/sellers/{sellerId}/products")
public class BuyerCatalogController {
    private final CatalogService catalog;
    public BuyerCatalogController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping
    public CatalogDtos.Page<CatalogDtos.Buyer> list(@PathVariable UUID buyerId,@PathVariable UUID sellerId,
            @RequestParam(required=false) String q,@RequestParam(required=false) UUID categoryId,@RequestParam(required=false) String sort,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth) {
        return catalog.buyerList(buyerId,sellerId,q,categoryId,sort,page,size,auth);
    }
    @GetMapping("/{productId}")
    public CatalogDtos.Buyer get(@PathVariable UUID buyerId,@PathVariable UUID sellerId,@PathVariable UUID productId,Authentication auth) {
        return catalog.buyerGet(buyerId,sellerId,productId,auth);
    }
    @GetMapping("/{productId}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID buyerId,@PathVariable UUID sellerId,@PathVariable UUID productId,
            @PathVariable UUID imageId,@RequestParam(required=false) Integer size,Authentication auth) {
        return PublicCatalogController.image(catalog.buyerImage(buyerId,sellerId,productId,imageId,size,auth));
    }
}

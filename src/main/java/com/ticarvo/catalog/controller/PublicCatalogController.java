package com.ticarvo.catalog.controller;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.service.PublicCatalogService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
public class PublicCatalogController {
    private final PublicCatalogService catalog;
    public PublicCatalogController(PublicCatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/products")
    public CatalogDtos.Page<CatalogDtos.PublicProduct> list(@RequestParam(required=false) UUID sellerCompanyId,
            @RequestParam(required=false) String q,@RequestParam(required=false) UUID categoryId,@RequestParam(required=false) String sort,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return catalog.list(sellerCompanyId,q,categoryId,sort,page,size);
    }
    @GetMapping("/products/{productId}")
    public CatalogDtos.PublicProduct get(@PathVariable UUID productId) {
        return catalog.get(productId);
    }
    @GetMapping("/products/{productId}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID productId,@PathVariable UUID imageId,@RequestParam(required=false) Integer size) {
        return image(catalog.image(productId,imageId,size));
    }
    @GetMapping("/categories")
    public List<CatalogDtos.CategoryNode> categories() { return catalog.categories(); }

    static ResponseEntity<byte[]> image(CatalogDtos.ImageContent content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).body(content.bytes());
    }
}

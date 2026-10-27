package com.ticarvo.catalog.controller;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.model.ProductStatus;
import com.ticarvo.catalog.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/companies/{sellerId}/products")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog=catalog; }
    @PostMapping
    public ResponseEntity<CatalogDtos.Owner> create(@PathVariable UUID sellerId,@Valid @RequestBody CatalogDtos.Create body,Authentication auth) {
        var value=catalog.create(sellerId,body,auth);
        return ResponseEntity.created(URI.create("/api/v1/companies/"+sellerId+"/products/"+value.id())).body(value);
    }
    @GetMapping
    public CatalogDtos.Page<CatalogDtos.Owner> list(@PathVariable UUID sellerId,@RequestParam(required=false) ProductStatus status,
            @RequestParam(required=false) String q,@RequestParam(required=false) UUID categoryId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth) {
        return catalog.list(sellerId,status,q,categoryId,page,size,auth);
    }
    @PostMapping(path="/{productId}/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CatalogDtos.Owner addImage(@PathVariable UUID sellerId,@PathVariable UUID productId,@RequestParam("file") MultipartFile file,
            @RequestParam long expectedVersion,Authentication auth) throws IOException {
        return catalog.addImage(sellerId,productId,expectedVersion,file.getBytes(),auth);
    }
    @PutMapping("/{productId}/images/order")
    public CatalogDtos.Owner reorderImages(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.ImageOrder body,Authentication auth) {
        return catalog.reorderImages(sellerId,productId,body,auth);
    }
    @DeleteMapping("/{productId}/images/{imageId}")
    public CatalogDtos.Owner removeImage(@PathVariable UUID sellerId,@PathVariable UUID productId,@PathVariable UUID imageId,
            @RequestParam long expectedVersion,Authentication auth) {
        return catalog.removeImage(sellerId,productId,imageId,expectedVersion,auth);
    }
    @GetMapping("/{productId}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID sellerId,@PathVariable UUID productId,@PathVariable UUID imageId,Authentication auth) {
        return PublicCatalogController.image(catalog.ownerImage(sellerId,productId,imageId,auth));
    }
    @GetMapping("/{productId}")
    public CatalogDtos.Owner get(@PathVariable UUID sellerId,@PathVariable UUID productId,Authentication auth) {
        return catalog.get(sellerId,productId,auth);
    }
    @PutMapping("/{productId}")
    public CatalogDtos.Owner edit(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Edit body,Authentication auth) {
        return catalog.edit(sellerId,productId,body,auth);
    }
    @PostMapping("/{productId}/publish")
    public CatalogDtos.Owner publish(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Version body,Authentication auth) {
        return catalog.transition(sellerId,productId,body.expectedVersion(),ProductStatus.PUBLISHED,auth);
    }
    @PostMapping("/{productId}/unpublish")
    public CatalogDtos.Owner unpublish(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Version body,Authentication auth) {
        return catalog.transition(sellerId,productId,body.expectedVersion(),ProductStatus.DRAFT,auth);
    }
    @PostMapping("/{productId}/archive")
    public CatalogDtos.Owner archive(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Version body,Authentication auth) {
        return catalog.transition(sellerId,productId,body.expectedVersion(),ProductStatus.ARCHIVED,auth);
    }
    @PutMapping("/{productId}/public-visibility")
    public CatalogDtos.Owner visibility(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Visibility body,Authentication auth) {
        return catalog.visibility(sellerId,productId,body,auth);
    }
    @PutMapping("/{productId}/stock")
    public CatalogDtos.Owner stock(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.Stock body,Authentication auth) {
        return catalog.stock(sellerId,productId,body,auth);
    }
    @GetMapping("/{productId}/buyers")
    public CatalogDtos.Buyers buyers(@PathVariable UUID sellerId,@PathVariable UUID productId,Authentication auth) {
        return catalog.buyers(sellerId,productId,auth);
    }
    @PutMapping("/{productId}/buyers")
    public CatalogDtos.Buyers replace(@PathVariable UUID sellerId,@PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.BuyersInput body,Authentication auth) {
        return catalog.replaceBuyers(sellerId,productId,body,auth);
    }
}

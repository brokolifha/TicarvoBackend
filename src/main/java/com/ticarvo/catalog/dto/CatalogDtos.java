package com.ticarvo.catalog.dto;
import com.ticarvo.catalog.model.ProductStatus;
import com.ticarvo.catalog.model.ProductUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

public final class CatalogDtos {
    private CatalogDtos() {}

    // Requests. Amount and taxIncluded stay raw JSON so the service can reject numbers and strings explicitly.
    public record PriceInput(@NotNull JsonNode amount,@NotBlank String currency,@NotNull JsonNode taxIncluded) {}
    /** Consumer price; always all taxes included, so there is no taxIncluded flag (ADR-010). */
    public record RetailPriceInput(@NotNull JsonNode amount,@NotBlank String currency) {}
    /** ADR-011. Omitted on create: VAT 20, PIECE, case pack 1, minimum 1; omitted on edit: unchanged. */
    public record DetailsInput(Integer vatRate,ProductUnit unit,@Min(1) @Max(10000) Integer casePack,@Min(1) @Max(100000) Integer minQuantity,
            @Pattern(regexp="[0-9]{8}|[0-9]{12,14}") String barcode,@Size(max=100) String brand) {}
    public record Create(@NotBlank @Size(max=64) String sku,@NotBlank @Size(max=200) String name,
            @Size(max=2000) String description,@NotNull @Valid PriceInput price,UUID categoryId,@Valid RetailPriceInput retailPrice,
            @Valid DetailsInput details) {}
    public record Edit(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=64) String sku,
            @NotBlank @Size(max=200) String name,@Size(max=2000) String description,@NotNull @Valid PriceInput price,UUID categoryId,
            @Valid RetailPriceInput retailPrice,@Valid DetailsInput details) {}
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {}
    /** null stops tracking stock (ADR-009). */
    public record Stock(@PositiveOrZero @Max(1000000000) Integer quantity) {}
    public record Visibility(@NotNull @PositiveOrZero Long expectedVersion,@NotNull Boolean publicVisible) {}
    public record BuyersInput(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @Size(max=100) List<@NotNull UUID> buyerCompanyIds) {}
    /** Every image of the product exactly once; the first one becomes the cover (ADR-007). */
    public record ImageOrder(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @Size(max=5) List<@NotNull UUID> imageIds) {}

    // Responses. Each audience has its own DTO; the public one never carries price or SKU.
    public record Price(String amount,String currency,boolean taxIncluded) {}
    public record Details(int vatRate,ProductUnit unit,int casePack,int minQuantity,String barcode,String brand) {}
    public record Owner(UUID id,UUID sellerCompanyId,String sku,String name,String description,ProductStatus status,
            boolean publicVisible,Price price,long version,Instant createdAt,Instant updatedAt,UUID categoryId,List<UUID> imageIds,Integer stockQuantity,
            Price retailPrice,Details details) {}
    /** availableQuantity is null when the seller does not track stock. */
    public record Buyer(UUID id,UUID sellerCompanyId,String sku,String name,String description,Price price,UUID categoryId,List<UUID> imageIds,Integer availableQuantity,Details details) {}
    /** retailPrice is the consumer price (null: discovery only); the B2B price and SKU never appear here (ADR-010). */
    public record PublicProduct(UUID id,UUID sellerCompanyId,String sellerName,String name,String description,UUID categoryId,List<UUID> imageIds,
            Price retailPrice,String brand,ProductUnit unit) {}
    public record Buyers(long productVersion,List<UUID> buyerCompanyIds) {}
    /** C-09: a hit of the buyer's search over all its sellers. */
    public record BuyerSearchItem(String sellerName,Buyer product) {}
    public record Page<T>(List<T> content,int page,int size,long totalElements) {}
    public record CategoryNode(UUID id,String code,String name,List<CategoryNode> children) {}
    /** Image bytes for the controller; not serialized as JSON. */
    public record ImageContent(String contentType,byte[] bytes) {}
}

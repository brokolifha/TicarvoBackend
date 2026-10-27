package com.ticarvo.catalog.service.mapper;
import com.ticarvo.catalog.dto.CatalogDtos;
import com.ticarvo.catalog.entity.Product;
import java.util.List;
import java.util.UUID;

public final class ProductMapper {
    private ProductMapper() {}
    public static CatalogDtos.Price price(Product p) {
        return new CatalogDtos.Price(p.amount().setScale(2).toPlainString(),p.currency(),p.taxIncluded());
    }
    /** Consumer prices always include all taxes (ADR-010). */
    public static CatalogDtos.Price retailPrice(Product p) {
        return p.retailAmount()==null ? null : new CatalogDtos.Price(p.retailAmount().setScale(2).toPlainString(),p.retailCurrency(),true);
    }
    public static CatalogDtos.Details details(Product p) {
        return new CatalogDtos.Details(p.vatRate(),p.unit(),p.casePack(),p.minQuantity(),p.barcode(),p.brand());
    }
    public static CatalogDtos.Owner owner(Product p,List<UUID> imageIds) {
        return new CatalogDtos.Owner(p.id(),p.seller(),p.sku(),p.name(),p.description(),p.status(),p.publicVisible(),
            price(p),p.version(),p.createdAt(),p.updatedAt(),p.categoryId(),imageIds,p.stockQuantity(),retailPrice(p),details(p));
    }
    public static CatalogDtos.Buyer buyer(Product p,List<UUID> imageIds) {
        return new CatalogDtos.Buyer(p.id(),p.seller(),p.sku(),p.name(),p.description(),price(p),p.categoryId(),imageIds,p.stockQuantity(),details(p));
    }
    public static CatalogDtos.PublicProduct publicDto(Product p,String sellerName,List<UUID> imageIds) {
        return new CatalogDtos.PublicProduct(p.id(),p.seller(),sellerName,p.name(),p.description(),p.categoryId(),imageIds,retailPrice(p),p.brand(),p.unit());
    }
}

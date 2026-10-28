package com.ticarvo.catalog.repository;
import com.ticarvo.catalog.entity.Product;
import com.ticarvo.catalog.entity.ProductGrant;
import com.ticarvo.catalog.model.ProductStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

/**
 * Search filters (ADR-007): {@code pattern} is a LIKE pattern with '!' as escape, or null for no text filter;
 * {@code anyCategory=false} restricts to {@code categories}.
 */
// shortcut: '%q%' scans the visible set; move public search to FULLTEXT or a search service when it grows.
public interface ProductRepository extends JpaRepository<Product,UUID> {
    Optional<Product> findByIdAndSellerCompanyId(UUID id,UUID seller);
    boolean existsBySellerCompanyIdAndSku(UUID seller,String sku);
    boolean existsBySellerCompanyIdAndSkuAndIdNot(UUID seller,String sku,UUID id);
    long countBySellerCompanyId(UUID seller);

    @Query("select p from Product p where p.sellerCompanyId=:seller and (:status is null or p.status=:status)"
        + " and (:pattern is null or p.name like :pattern escape '!' or p.sku like :pattern escape '!')"
        + " and (:anyCategory = true or p.categoryId in :categories)")
    Page<Product> ownerList(@Param("seller") UUID seller,@Param("status") ProductStatus status,@Param("pattern") String pattern,
        @Param("anyCategory") boolean anyCategory,@Param("categories") Collection<UUID> categories,Pageable page);

    // Read-only filter on the company table: a suspended seller (ADR-008) or one without the selling permission (ADR-011)
    // disappears for everyone else and cannot take orders.
    String ACTIVE_SELLER=" and not exists(select c from Company c where c.id=p.sellerCompanyId and (c.suspendedAt is not null or c.sellingEnabled=false))";

    // Buyer visibility: same seller, PUBLISHED and an explicit grant. The relationship check happens in the service.
    @Query("select p from Product p where p.sellerCompanyId=:seller"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER
        + " and exists(select g from ProductGrant g where g.productId=p.id and g.buyerCompanyId=:buyer)"
        + " and (:pattern is null or p.name like :pattern escape '!' or p.sku like :pattern escape '!')"
        + " and (:anyCategory = true or p.categoryId in :categories)")
    Page<Product> buyerList(@Param("seller") UUID seller,@Param("buyer") UUID buyer,@Param("pattern") String pattern,
        @Param("anyCategory") boolean anyCategory,@Param("categories") Collection<UUID> categories,Pageable page);

    @Query("select p from Product p where p.id=:id and p.sellerCompanyId=:seller"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER
        + " and exists(select g from ProductGrant g where g.productId=p.id and g.buyerCompanyId=:buyer)")
    Optional<Product> buyerDetail(@Param("id") UUID id,@Param("seller") UUID seller,@Param("buyer") UUID buyer);

    // C-09: one search over every seller that still trades with this buyer. The relationship is re-checked here
    // because a revocation leaves the grants in place; like ACTIVE_SELLER this is a read-only cross-module filter.
    @Query("select p from Product p where p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER
        + " and exists(select g from ProductGrant g where g.productId=p.id and g.buyerCompanyId=:buyer)"
        + " and exists(select r from TradeRelationship r where r.buyerCompanyId=:buyer and r.sellerCompanyId=p.sellerCompanyId"
        + " and r.status=com.ticarvo.trade.model.TradeStatus.APPROVED)"
        + " and (:pattern is null or p.name like :pattern escape '!' or p.sku like :pattern escape '!')"
        + " and (:anyCategory = true or p.categoryId in :categories)")
    Page<Product> buyerSearch(@Param("buyer") UUID buyer,@Param("pattern") String pattern,
        @Param("anyCategory") boolean anyCategory,@Param("categories") Collection<UUID> categories,Pageable page);

    // Same visibility as buyerDetail, for the order checkout (ADR-009).
    @Query("select p from Product p where p.id in :ids and p.sellerCompanyId=:seller"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER
        + " and exists(select g from ProductGrant g where g.productId=p.id and g.buyerCompanyId=:buyer)")
    List<Product> buyerOrderable(@Param("ids") Collection<UUID> ids,@Param("seller") UUID seller,@Param("buyer") UUID buyer);

    // Consumers buy what anyone can see, at the retail price (ADR-010).
    @Query("select p from Product p where p.id in :ids and p.sellerCompanyId=:seller and p.publicVisible=true"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED and p.retailAmount is not null" + ACTIVE_SELLER)
    List<Product> retailOrderable(@Param("ids") Collection<UUID> ids,@Param("seller") UUID seller);

    // Public search matches the name only: the SKU is not public, so searching it would leak it.
    @Query("select p from Product p where p.publicVisible=true"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER
        + " and (:seller is null or p.sellerCompanyId=:seller)"
        + " and (:pattern is null or p.name like :pattern escape '!')"
        + " and (:anyCategory = true or p.categoryId in :categories)")
    Page<Product> publicList(@Param("seller") UUID seller,@Param("pattern") String pattern,
        @Param("anyCategory") boolean anyCategory,@Param("categories") Collection<UUID> categories,Pageable page);

    // ADR-012: a seller's consumer terms are public once it sells something to consumers.
    @Query("select count(p)>0 from Product p where p.sellerCompanyId=:seller and p.publicVisible=true"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED and p.retailAmount is not null" + ACTIVE_SELLER)
    boolean sellsToConsumers(@Param("seller") UUID seller);

    @Query("select p from Product p where p.id=:id and p.publicVisible=true"
        + " and p.status=com.ticarvo.catalog.model.ProductStatus.PUBLISHED" + ACTIVE_SELLER)
    Optional<Product> publicDetail(@Param("id") UUID id);
}

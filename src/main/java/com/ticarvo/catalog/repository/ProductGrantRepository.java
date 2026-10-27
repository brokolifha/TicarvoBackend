package com.ticarvo.catalog.repository;
import com.ticarvo.catalog.entity.ProductGrant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ProductGrantRepository extends JpaRepository<ProductGrant,ProductGrant.Key> {
    @Query("select g.buyerCompanyId from ProductGrant g where g.productId=:product order by g.buyerCompanyId")
    List<UUID> buyers(@Param("product") UUID product);

    @Modifying
    @Query("delete from ProductGrant g where g.productId=:product")
    void removeAll(@Param("product") UUID product);
}

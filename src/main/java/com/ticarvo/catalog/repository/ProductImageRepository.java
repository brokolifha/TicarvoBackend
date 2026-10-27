package com.ticarvo.catalog.repository;
import com.ticarvo.catalog.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ProductImageRepository extends JpaRepository<ProductImage,UUID> {
    List<ProductImage> findByProductIdOrderByPositionAsc(UUID productId);
    List<ProductImage> findByProductIdInOrderByPositionAsc(Collection<UUID> productIds);
    Optional<ProductImage> findByIdAndProductId(UUID id, UUID productId);
}

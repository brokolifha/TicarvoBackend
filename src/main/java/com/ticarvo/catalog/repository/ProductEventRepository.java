package com.ticarvo.catalog.repository;
import com.ticarvo.catalog.entity.ProductEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProductEventRepository extends JpaRepository<ProductEvent,UUID> {}

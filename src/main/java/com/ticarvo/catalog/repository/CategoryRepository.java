package com.ticarvo.catalog.repository;
import com.ticarvo.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CategoryRepository extends JpaRepository<Category,UUID> {
    List<Category> findAllByOrderByPositionAscCodeAsc();
    List<Category> findByParentId(UUID parentId);
}

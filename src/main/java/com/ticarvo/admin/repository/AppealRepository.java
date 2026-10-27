package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.Appeal;
import com.ticarvo.admin.model.Moderation.AppealStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;
public interface AppealRepository extends JpaRepository<Appeal, UUID> {
    Page<Appeal> findByStatus(AppealStatus status, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appeal a where a.id=:id")
    Optional<Appeal> lockById(@Param("id") UUID id);
}

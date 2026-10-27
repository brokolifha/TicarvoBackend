package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.Complaint;
import com.ticarvo.admin.model.Moderation.ComplaintStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {
    Page<Complaint> findByReporterUserId(UUID reporter, Pageable page);
    Optional<Complaint> findByIdAndReporterUserId(UUID id, UUID reporter);
    Page<Complaint> findByStatus(ComplaintStatus status, Pageable page);
    long countByReporterUserIdAndStatusIn(UUID reporter, Collection<ComplaintStatus> statuses);
    @Query("select count(c)>0 from Complaint c where c.reporterUserId=:reporter and c.companyId=:company"
        + " and (c.productId=:product or (:product is null and c.productId is null)) and c.status in :statuses")
    boolean existsSameTarget(@Param("reporter") UUID reporter, @Param("company") UUID company, @Param("product") UUID product,
        @Param("statuses") Collection<ComplaintStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.id=:id")
    Optional<Complaint> lockById(@Param("id") UUID id);
}

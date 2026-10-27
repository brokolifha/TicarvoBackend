package com.ticarvo.company.repository;
import com.ticarvo.company.entity.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    /** Public seller directory, optionally narrowed by name (C-10). */
    @Query("select c from Company c where c.publicSeller=true and c.suspendedAt is null and (:pattern is null or c.name like :pattern escape '!')")
    Page<Company> directory(@Param("pattern") String pattern, Pageable pageable);
    Optional<Company> findByIdAndPublicSellerTrueAndSuspendedAtIsNull(UUID id);
    boolean existsByIdAndSuspendedAtIsNotNull(UUID id);
    @Query("select c from Company c where c.name like :pattern escape '!'")
    Page<Company> search(@Param("pattern") String pattern, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") UUID id);
}

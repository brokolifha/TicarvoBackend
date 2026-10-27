package com.ticarvo.company.repository;
import com.ticarvo.company.entity.Membership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;
public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    @Query("select m.role from Membership m where m.company.id=:companyId and m.userId=:userId")
    Optional<String> currentRole(@org.springframework.data.repository.query.Param("companyId") UUID companyId,
        @org.springframework.data.repository.query.Param("userId") UUID userId);
    @EntityGraph(attributePaths = "company")
    Page<Membership> findByUserId(UUID userId, Pageable pageable);
    @EntityGraph(attributePaths = "company")
    Optional<Membership> findByCompanyIdAndUserId(UUID companyId, UUID userId);
    java.util.List<Membership> findByCompanyIdOrderByCreatedAtAscIdAsc(UUID companyId);
    long countByCompanyId(UUID companyId);
}

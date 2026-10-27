package com.ticarvo.company.repository;
import com.ticarvo.company.entity.MemberInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MemberInvitationRepository extends JpaRepository<MemberInvitation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MemberInvitation i where i.tokenHash = :hash")
    Optional<MemberInvitation> findForUpdate(@Param("hash") String hash);
    @Query("select i.companyId from MemberInvitation i where i.tokenHash = :hash")
    Optional<UUID> companyIdForHash(@Param("hash") String hash);
    List<MemberInvitation> findByCompanyIdOrderByCreatedAtDescIdAsc(UUID companyId);
    Optional<MemberInvitation> findByIdAndCompanyId(UUID id, UUID companyId);
    long countByCompanyId(UUID companyId);
    @Modifying
    @Query("delete from MemberInvitation i where i.companyId = :company and i.email = :email")
    void deleteByCompanyAndEmail(@Param("company") UUID company, @Param("email") String email);
}

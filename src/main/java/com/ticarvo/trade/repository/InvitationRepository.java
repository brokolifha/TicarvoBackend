package com.ticarvo.trade.repository;
import com.ticarvo.trade.entity.SellerInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface InvitationRepository extends JpaRepository<SellerInvitation,UUID> {
    @Query("select i.sellerCompanyId from SellerInvitation i where i.codeHash=:hash")
    Optional<UUID> sellerIdForHash(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SellerInvitation i where i.sellerCompanyId=:seller")
    Optional<SellerInvitation> findForUpdate(@Param("seller") UUID seller);
}

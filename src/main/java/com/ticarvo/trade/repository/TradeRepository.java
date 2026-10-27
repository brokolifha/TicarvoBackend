package com.ticarvo.trade.repository;
import com.ticarvo.trade.entity.TradeRelationship;
import com.ticarvo.trade.model.TradeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface TradeRepository extends JpaRepository<TradeRelationship,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TradeRelationship r where r.buyerCompanyId=:buyer and r.sellerCompanyId=:seller")
    Optional<TradeRelationship> findPairForUpdate(@Param("buyer") UUID buyer,@Param("seller") UUID seller);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TradeRelationship r where r.id=:id")
    Optional<TradeRelationship> findForUpdate(@Param("id") UUID id);
    @Query("select r.sellerCompanyId from TradeRelationship r where r.id=:id and (r.buyerCompanyId=:company or r.sellerCompanyId=:company)")
    Optional<UUID> visibleSellerId(@Param("id") UUID id,@Param("company") UUID company);
    @Query("select r from TradeRelationship r where r.id=:id and (r.buyerCompanyId=:company or r.sellerCompanyId=:company)")
    Optional<TradeRelationship> findVisible(@Param("id") UUID id,@Param("company") UUID company);
    @Query("select r from TradeRelationship r where r.buyerCompanyId=:company and (:status is null or r.status=:status)")
    Page<TradeRelationship> outgoing(@Param("company") UUID company,@Param("status") TradeStatus status,Pageable page);
    @Query("select r from TradeRelationship r where r.sellerCompanyId=:company and (:status is null or r.status=:status)")
    Page<TradeRelationship> incoming(@Param("company") UUID company,@Param("status") TradeStatus status,Pageable page);
    boolean existsByBuyerCompanyIdAndSellerCompanyIdAndStatus(UUID buyer,UUID seller,TradeStatus status);
}

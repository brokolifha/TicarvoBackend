package com.ticarvo.order.repository;

import com.ticarvo.order.entity.PurchaseOrder;
import com.ticarvo.order.model.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {
    @Query("select o from PurchaseOrder o where o.buyerCompanyId=:company and (:status is null or o.status=:status)")
    Page<PurchaseOrder> buyerList(@Param("company") UUID company, @Param("status") OrderStatus status, Pageable page);
    @Query("select o from PurchaseOrder o where o.sellerCompanyId=:company and (:status is null or o.status=:status)")
    Page<PurchaseOrder> sellerList(@Param("company") UUID company, @Param("status") OrderStatus status, Pageable page);
    @Query("select o from PurchaseOrder o where o.consumerUserId=:user and (:status is null or o.status=:status)")
    Page<PurchaseOrder> consumerList(@Param("user") UUID user, @Param("status") OrderStatus status, Pageable page);
    List<PurchaseOrder> findByCheckoutIdOrderBySellerCompanyId(UUID checkoutId);
    @Query("select o.sellerCompanyId from PurchaseOrder o where o.id=:id and o.consumerUserId=:user")
    Optional<UUID> sellerOfConsumerOrder(@Param("id") UUID id, @Param("user") UUID user);
    /** Scalar on purpose: nothing enters the persistence context before the locking read. */
    @Query("select o.sellerCompanyId from PurchaseOrder o where o.id=:id"
        + " and ((:asSeller=true and o.sellerCompanyId=:company) or (:asSeller=false and o.buyerCompanyId=:company))")
    Optional<UUID> sellerOf(@Param("id") UUID id, @Param("company") UUID company, @Param("asSeller") boolean asSeller);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id=:id")
    Optional<PurchaseOrder> lockById(@Param("id") UUID id);
}

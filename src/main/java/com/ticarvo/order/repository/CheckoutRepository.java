package com.ticarvo.order.repository;

import com.ticarvo.order.entity.Checkout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface CheckoutRepository extends JpaRepository<Checkout, UUID> {
    Optional<Checkout> findByBuyerCompanyIdAndIdempotencyKey(UUID buyerCompanyId, String idempotencyKey);
    Optional<Checkout> findByConsumerUserIdAndIdempotencyKey(UUID consumerUserId, String idempotencyKey);
}

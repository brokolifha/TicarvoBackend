package com.ticarvo.order.repository;

import com.ticarvo.order.entity.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {
    List<OrderLine> findByOrderIdInOrderByOrderIdAscLineNoAsc(Collection<UUID> orderIds);
}

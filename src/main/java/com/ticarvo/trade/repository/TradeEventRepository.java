package com.ticarvo.trade.repository;
import com.ticarvo.trade.entity.TradeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TradeEventRepository extends JpaRepository<TradeEvent,UUID> {}

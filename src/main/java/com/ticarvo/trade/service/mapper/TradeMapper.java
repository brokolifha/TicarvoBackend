package com.ticarvo.trade.service.mapper;
import com.ticarvo.company.dto.CompanySummary;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.entity.TradeRelationship;
import java.util.Map;
import java.util.UUID;
public final class TradeMapper {
 private TradeMapper() {}
 public static TradeDtos.Relationship toResponse(TradeRelationship value,Map<UUID,CompanySummary> companies) {
  return new TradeDtos.Relationship(value.id(),value.buyerId(),companies.get(value.buyerId()).name(),value.sellerId(),
   companies.get(value.sellerId()).name(),value.status(),value.version(),value.createdAt(),value.updatedAt());
 }
}

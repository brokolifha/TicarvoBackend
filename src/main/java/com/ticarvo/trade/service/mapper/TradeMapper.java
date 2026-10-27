package com.ticarvo.trade.service.mapper;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.entity.TradeRelationship;
public final class TradeMapper {
 private TradeMapper() {}
 public static TradeDtos.Relationship toResponse(TradeRelationship value) {
  return new TradeDtos.Relationship(value.id(),value.buyerId(),value.sellerId(),value.status(),value.version(),value.createdAt(),value.updatedAt());
 }
}

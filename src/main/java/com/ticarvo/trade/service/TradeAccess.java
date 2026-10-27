package com.ticarvo.trade.service;

import java.util.UUID;
public interface TradeAccess { boolean hasApprovedRelationship(UUID buyerId, UUID sellerId); }

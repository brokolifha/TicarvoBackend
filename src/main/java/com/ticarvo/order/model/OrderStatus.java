package com.ticarvo.order.model;

/** ADR-009 state machine; RETURN_REQUESTED and RETURNED are the consumer withdrawal path (ADR-010). */
public enum OrderStatus { PLACED, ACCEPTED, REJECTED, CANCELLED, SHIPPED, DELIVERED, RETURN_REQUESTED, RETURNED }

package com.ticarvo.order.model;

/** Payment happens outside Ticarvo; the seller only records it (ADR-009, ADR-010). */
public enum PaymentStatus { UNPAID, PAID, REFUNDED }

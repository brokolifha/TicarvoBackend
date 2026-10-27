package com.ticarvo.order.model;

/** Offline consumer payment methods; card payment waits for a licensed payment provider (ADR-010, REL-M7-PAYMENT). */
public enum PaymentMethod { BANK_TRANSFER, CASH_ON_DELIVERY }

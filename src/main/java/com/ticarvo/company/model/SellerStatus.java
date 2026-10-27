package com.ticarvo.company.model;

/** ADR-011: NONE -> PENDING (applied) -> VERIFIED or REJECTED; a rejected company may apply again. */
public enum SellerStatus { NONE, PENDING, VERIFIED, REJECTED }

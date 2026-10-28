package com.ticarvo.company.dto;

import java.util.UUID;

/**
 * ADR-012: what a buyer must know before ordering from a seller: who sells (pre-information, distance contracts
 * regulation) and the seller's rule for this channel in TRY. Tax details and the IBAN only reach approved dealers.
 */
public record SaleTerms(UUID sellerId, String name, String legalName, String address, String city, String phone,
        String taxId, String taxOffice, String iban, String minimumOrder, String shippingFee, String freeShippingFrom) {}

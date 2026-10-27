package com.ticarvo.company.dto;

import java.util.*;
/** Public seller detail; verified means a platform admin checked the billing details (ADR-011). */
public record SellerResponse(UUID id, String name, ShippingTerms shippingTerms, String description, String website, String city,
    boolean hasLogo, boolean verified) {}

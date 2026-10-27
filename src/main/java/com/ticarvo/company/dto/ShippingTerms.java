package com.ticarvo.company.dto;

import jakarta.validation.constraints.Pattern;
/**
 * A seller's order rules per channel, in TRY (ADR-011); any of them may be null. Below the minimum an order is refused;
 * the shipping fee applies until the goods subtotal reaches the free-shipping threshold.
 */
public record ShippingTerms(
    @Pattern(regexp = MONEY) String b2bMinimumOrder, @Pattern(regexp = MONEY) String b2bShippingFee, @Pattern(regexp = MONEY) String b2bFreeShippingFrom,
    @Pattern(regexp = MONEY) String b2cMinimumOrder, @Pattern(regexp = MONEY) String b2cShippingFee, @Pattern(regexp = MONEY) String b2cFreeShippingFrom) {
    public static final String MONEY = "[0-9]{1,15}(\\.[0-9]{1,2})?";
}

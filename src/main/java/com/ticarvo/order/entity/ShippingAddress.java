package com.ticarvo.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Consumer delivery address copied onto the order; only the consumer and the seller of that order see it (ADR-010). */
@Embeddable
public record ShippingAddress(
    @Column(name = "ship_recipient", length = 100) String recipient,
    @Column(name = "ship_phone", length = 20) String phone,
    @Column(name = "ship_address", length = 300) String address,
    @Column(name = "ship_district", length = 100) String district,
    @Column(name = "ship_city", length = 100) String city,
    @Column(name = "ship_postal_code", length = 10) String postalCode) {}

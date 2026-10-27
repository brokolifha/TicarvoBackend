package com.ticarvo.company.dto;

import com.ticarvo.company.model.SellerStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** ADR-011 company profile: billing details checked by a platform admin, and the public showcase. */
public final class ProfileDtos {
    private ProfileDtos() {}
    /** Every field is needed to apply as a seller; locked while the application is PENDING or VERIFIED. */
    public record Billing(@Size(max = 200) String legalName,
        @Pattern(regexp = "[0-9]{10}|[1-9][0-9]{10}") String taxId,
        @Size(max = 100) String taxOffice, @Size(max = 500) String address, @Size(max = 60) String city,
        @Pattern(regexp = "\\+?[0-9]{10,15}") String phone) {}
    /** Replaces the whole profile; a null field clears it. */
    public record Edit(@NotNull @Valid Billing billing, @Size(max = 2000) String description,
        @Size(max = 200) @Pattern(regexp = "https?://\\S+") String website) {}
    public record Profile(UUID companyId, String name, Billing billing, String description, String website, boolean hasLogo,
        SellerStatus sellerStatus, String sellerNote, Instant sellerAppliedAt, boolean sellingEnabled) {}
    public record Logo(String contentType, byte[] bytes) {}
}

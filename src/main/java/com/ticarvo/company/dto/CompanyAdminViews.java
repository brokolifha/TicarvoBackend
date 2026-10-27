package com.ticarvo.company.dto;

import java.time.Instant;
import java.util.UUID;
/** What a platform admin may see about companies (ADR-008): no catalog, price or trade data. */
public final class CompanyAdminViews {
    private CompanyAdminViews() {}
    public record Company(UUID id, String name, Instant createdAt, boolean publicSeller, boolean suspended) {}
    public record Membership(UUID companyId, String companyName, UUID userId, String role) {}
}

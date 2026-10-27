package com.ticarvo.company.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class MemberDtos {
    private MemberDtos() {}
    public record Member(UUID userId, String email, String displayName, String role, Instant joinedAt) {}
    public record Invite(@NotBlank @Email @Size(max = 254) String email, @NotNull @Pattern(regexp = "ADMIN|MEMBER") String role) {}
    public record Invitation(UUID id, String email, String role, Instant createdAt, Instant expiresAt) {}
    public record Accept(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "Accept[REDACTED]"; }
    }
    public record Accepted(UUID companyId, String role) {}
    public record RoleChange(@NotNull @Pattern(regexp = "ADMIN|MEMBER") String role) {}
    public record Transfer(@NotNull UUID userId) {}
}

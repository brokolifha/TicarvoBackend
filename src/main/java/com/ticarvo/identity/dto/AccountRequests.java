package com.ticarvo.identity.dto;

import jakarta.validation.constraints.*;
public final class AccountRequests {
    private AccountRequests() {}
    public record Token(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "Token[REDACTED]"; }
    }
    /** A 6-digit TOTP code or a recovery code. */
    public record MfaCode(@NotBlank @Size(max = 32) String code) {
        @Override public String toString() { return "MfaCode[REDACTED]"; }
    }
    public record EmailAddress(@NotBlank @Email @Size(max = 254) String email) {}
    // C-03 account settings. Every change of credentials asks for the current password.
    public record Profile(@NotBlank @Size(max = 100) String displayName) {}
    public record PasswordChange(@NotBlank @Size(max = 72) String currentPassword, @NotBlank @Size(min = 12, max = 72) String newPassword) {
        @Override public String toString() { return "PasswordChange[REDACTED]"; }
    }
    public record EmailChange(@NotBlank @Email @Size(max = 254) String newEmail, @NotBlank @Size(max = 72) String currentPassword) {
        @Override public String toString() { return "EmailChange[REDACTED]"; }
    }
    public record PasswordReset(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {
        @Override public String toString() { return "PasswordReset[REDACTED]"; }
    }
}

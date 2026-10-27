package com.ticarvo.identity.dto;

import jakarta.validation.constraints.*;
public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
    @NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=100) String displayName) {
    @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
package com.ticarvo.identity.dto;

import java.time.Instant;
import java.util.UUID;
/** What a platform admin may see about an account (ADR-008): never password, tokens or MFA secrets. */
public record AccountView(UUID id, String email, String displayName, Instant createdAt,
    boolean emailVerified, boolean mfaEnabled, boolean suspended) {}

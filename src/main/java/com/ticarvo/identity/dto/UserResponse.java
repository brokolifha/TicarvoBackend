package com.ticarvo.identity.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record UserResponse(UUID id, String email, String displayName, boolean mfaEnabled) {}

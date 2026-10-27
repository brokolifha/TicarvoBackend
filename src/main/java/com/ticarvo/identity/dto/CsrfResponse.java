package com.ticarvo.identity.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record CsrfResponse(String headerName, String token) {}

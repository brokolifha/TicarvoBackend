package com.ticarvo.company.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record CompanyResponse(UUID id, String name, String role, Instant createdAt, boolean suspended) {}

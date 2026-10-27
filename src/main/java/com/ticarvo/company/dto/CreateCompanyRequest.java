package com.ticarvo.company.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record CreateCompanyRequest(@NotBlank @Size(max = 200) String name) {}

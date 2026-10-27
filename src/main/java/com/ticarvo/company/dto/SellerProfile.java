package com.ticarvo.company.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record SellerProfile(UUID companyId, boolean listed) {}

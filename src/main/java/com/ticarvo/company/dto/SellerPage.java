package com.ticarvo.company.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record SellerPage(List<SellerResponse> content, int page, int size, long totalElements) {}

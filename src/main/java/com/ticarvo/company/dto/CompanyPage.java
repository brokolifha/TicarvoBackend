package com.ticarvo.company.dto;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public record CompanyPage(List<CompanyResponse> content, int page, int size, long totalElements) {}

package com.ticarvo.company.service;
import com.ticarvo.company.dto.SellerPage;
import com.ticarvo.company.dto.SellerProfile;
import com.ticarvo.company.dto.SellerResponse;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.validation.PageValidation;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SellerDirectoryService {
    private final CompanyRepository companies;
    private final CompanyAccessService access;
    public SellerDirectoryService(CompanyRepository companies, CompanyAccessService access) {
        this.companies = companies; this.access = access;
    }
    @Transactional(readOnly = true)
    public SellerPage list(String q, int page, int size) {
        PageValidation.validatePage(page, size);
        var result = companies.directory(com.ticarvo.shared.validation.SearchText.likePattern(q), PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
        return new SellerPage(result.stream().map(c -> new SellerResponse(c.getId(), c.getName())).toList(), page, size, result.getTotalElements());
    }
    @Transactional(readOnly = true)
    public SellerResponse get(UUID id) {
        var company = companies.findByIdAndPublicSellerTrueAndSuspendedAtIsNull(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found."));
        return new SellerResponse(company.getId(), company.getName());
    }
    @Transactional(readOnly = true)
    public SellerProfile profile(UUID id, Authentication authentication) {
        access.requireMember(id, authentication);
        return new SellerProfile(id, access.summary(id).listed());
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SellerProfile update(UUID id, boolean listed, Authentication authentication) {
        access.requireAdmin(id, authentication);
        access.lockSeller(id);
        // ARCH-M43-01: re-read the role after the lock.
        access.requireAdmin(id, authentication);
        var company = companies.findById(id).orElseThrow();
        company.setPublicSeller(listed);
        return new SellerProfile(id, listed);
    }
}

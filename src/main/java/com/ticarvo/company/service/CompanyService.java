package com.ticarvo.company.service;
import com.ticarvo.company.dto.CompanyPage;
import com.ticarvo.company.dto.CompanyResponse;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.entity.Membership;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class CompanyService {
    private final CompanyRepository companies;
    private final MembershipRepository memberships;
    private final CurrentUserProvider identity;
    private final PrincipalRateLimit limits;
    public CompanyService(CompanyRepository companies, MembershipRepository memberships, CurrentUserProvider identity, PrincipalRateLimit limits) {
        this.limits = limits;
        this.companies = companies; this.memberships = memberships; this.identity = identity;
    }
    @Transactional
    public CompanyResponse create(String name, Authentication authentication) {
        var userId = identity.currentUserId(authentication);
        limits.check(PrincipalRateLimit.Operation.COMPANY_CREATE, userId);
        var company = companies.save(new Company(name.strip()));
        return response(memberships.save(new Membership(company, userId)));
    }
    @Transactional(readOnly = true)
    public CompanyResponse get(UUID id, Authentication authentication) {
        return response(memberships.findByCompanyIdAndUserId(id, identity.currentUserId(authentication))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found.")));
    }
    @Transactional(readOnly = true)
    public CompanyPage list(int page, int size, Authentication authentication) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Page must be 0..100000 and size 1..100.");
        }
        var result = memberships.findByUserId(identity.currentUserId(authentication),
            PageRequest.of(page, size, Sort.by("company.createdAt").descending().and(Sort.by("id"))));
        return new CompanyPage(result.map(CompanyService::response).getContent(), page, size, result.getTotalElements());
    }
    private static CompanyResponse response(Membership membership) {
        var company = membership.getCompany();
        return new CompanyResponse(company.getId(), company.getName(), membership.getRole(), company.getCreatedAt(), company.isSuspended());
    }
}

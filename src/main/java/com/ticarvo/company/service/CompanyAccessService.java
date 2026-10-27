package com.ticarvo.company.service;
import com.ticarvo.company.dto.CompanySummary;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.shared.exception.ApiException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** The company module boundary; no persistence entities escape this API. */
@Service
public class CompanyAccessService implements CompanyAccess {
    private final CompanyRepository companies;
    private final MembershipRepository memberships;
    private final CurrentUserProvider identity;
    private final EntityManager entityManager;
    public CompanyAccessService(CompanyRepository companies, MembershipRepository memberships,
            CurrentUserProvider identity, EntityManager entityManager) {
        this.companies = companies; this.memberships = memberships; this.identity = identity; this.entityManager = entityManager;
    }
    @Transactional(readOnly = true)
    public UUID requireOwner(UUID companyId, Authentication authentication) {
        return require(companyId, authentication, OWNER, "OWNER_REQUIRED", "Company owner permission is required.");
    }
    @Transactional(readOnly = true)
    public UUID requireAdmin(UUID companyId, Authentication authentication) {
        return require(companyId, authentication, ADMIN, "ADMIN_REQUIRED", "Company admin permission is required.");
    }
    @Transactional(readOnly = true)
    public UUID requireMember(UUID companyId, Authentication authentication) {
        return require(companyId, authentication, MEMBER, "MEMBER_REQUIRED", "Company membership is required.");
    }
    static final String OWNER = "OWNER", ADMIN = "ADMIN", MEMBER = "MEMBER";
    static final int MAX_SUMMARIES = 1000;
    static int rank(String role) {
        return switch (role) { case OWNER -> 3; case ADMIN -> 2; case MEMBER -> 1; default -> 0; };
    }
    private UUID require(UUID companyId, Authentication authentication, String minimum, String code, String detail) {
        UUID userId = identity.currentUserId(authentication);
        var role = memberships.currentRole(companyId, userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found."));
        if (rank(role) < rank(minimum)) throw new ApiException(HttpStatus.FORBIDDEN, code, detail);
        // A suspended company is frozen for its own members too (ADR-008); data is kept.
        if (companies.existsByIdAndSuspendedAtIsNotNull(companyId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "COMPANY_SUSPENDED", "The company is suspended.");
        }
        return userId;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanySummary lockSeller(UUID sellerId) {
        var company = companies.findByIdForUpdate(sellerId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found."));
        // A preceding membership graph may already have attached this company to the persistence context.
        entityManager.refresh(company, LockModeType.PESSIMISTIC_WRITE);
        return summary(company);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanySummary lockSellerForRead(UUID sellerId) {
        var company = companies.findById(sellerId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found."));
        entityManager.refresh(company, LockModeType.PESSIMISTIC_READ);
        return summary(company);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.Map<UUID,CompanySummary> lockCompanies(java.util.Collection<UUID> companyIds) {
        var result = new java.util.LinkedHashMap<UUID,CompanySummary>();
        for (UUID id : new java.util.TreeSet<>(companyIds)) {
            companies.findByIdForUpdate(id).ifPresent(company -> {
                entityManager.refresh(company, LockModeType.PESSIMISTIC_WRITE);
                result.put(id, summary(company));
            });
        }
        return result;
    }
    @Transactional(readOnly = true)
    public com.ticarvo.company.dto.ShippingTerms shippingTerms(UUID companyId) {
        return companies.findById(companyId).map(CompanyAccessService::terms)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found."));
    }
    static com.ticarvo.company.dto.ShippingTerms terms(Company company) {
        var t = java.util.Arrays.stream(company.shippingTerms()).map(v -> v == null ? null : v.setScale(2).toPlainString()).toArray(String[]::new);
        return new com.ticarvo.company.dto.ShippingTerms(t[0], t[1], t[2], t[3], t[4], t[5]);
    }
    @Transactional(readOnly = true)
    public CompanySummary summary(UUID companyId) {
        var company = companies.findById(companyId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found."));
        return summary(company);
    }
    static CompanySummary summary(Company company) {
        // Listed means visible to buyers: it also needs the selling permission (ADR-011).
        return new CompanySummary(company.getId(), company.getName(), company.isPublicSeller() && company.isSellingEnabled(), company.isSuspended());
    }
    @Transactional(readOnly = true)
    public java.util.Map<UUID,CompanySummary> summaries(java.util.Collection<UUID> ids) {
        if(ids==null || ids.size()>MAX_SUMMARIES || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("At most "+MAX_SUMMARIES+" company IDs.");
        }
        java.util.Map<UUID,CompanySummary> result=new java.util.HashMap<>();
        // In chunks of 100: a page of 100 orders names up to 101 companies (A-01), and each IN list stays small.
        var list=new java.util.ArrayList<>(new java.util.LinkedHashSet<>(ids));
        for(int i=0;i<list.size();i+=100) {
            companies.findAllById(list.subList(i,Math.min(i+100,list.size()))).forEach(company -> result.put(company.getId(),summary(company)));
        }
        return java.util.Map.copyOf(result);
    }
}

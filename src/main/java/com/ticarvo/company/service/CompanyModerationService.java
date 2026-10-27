package com.ticarvo.company.service;

import com.ticarvo.company.dto.CompanyAdminViews;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.entity.Membership;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompanyModerationService implements CompanyModeration {
    private final CompanyRepository companies;
    private final MembershipRepository memberships;
    private final Clock clock;
    public CompanyModerationService(CompanyRepository companies, MembershipRepository memberships, Clock clock) {
        this.companies = companies; this.memberships = memberships; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Page<CompanyAdminViews.Company> search(String likePattern, int page, int size) {
        var request = PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
        return (likePattern == null ? companies.findAll(request) : companies.search(likePattern, request)).map(CompanyModerationService::view);
    }
    @Transactional(readOnly = true)
    public Optional<CompanyAdminViews.Company> find(UUID companyId) { return companies.findById(companyId).map(CompanyModerationService::view); }
    @Transactional(readOnly = true)
    public List<CompanyAdminViews.Membership> members(UUID companyId) {
        return memberships.findByCompanyIdOrderByCreatedAtAscIdAsc(companyId).stream().map(CompanyModerationService::membership).toList();
    }
    @Transactional(readOnly = true)
    public List<CompanyAdminViews.Membership> companiesOf(UUID userId) {
        // shortcut: first 100 companies only; page this if a user ever belongs to more.
        return memberships.findByUserId(userId, PageRequest.of(0, 100, Sort.by("company.name")))
            .map(CompanyModerationService::membership).getContent();
    }
    @Transactional(readOnly = true)
    public List<UUID> managerIds(UUID companyId) {
        return memberships.findByCompanyIdOrderByCreatedAtAscIdAsc(companyId).stream()
            .filter(m -> CompanyAccessService.rank(m.getRole()) >= CompanyAccessService.rank(CompanyAccessService.ADMIN))
            .map(Membership::getUserId).toList();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAdminViews.Company suspend(UUID companyId) {
        var company = lock(companyId);
        if (company.isSuspended()) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUSPENDED", "The company is already suspended.");
        company.suspend(clock.instant());
        return view(company);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAdminViews.Company restore(UUID companyId) {
        var company = lock(companyId);
        if (!company.isSuspended()) throw new ApiException(HttpStatus.CONFLICT, "NOT_SUSPENDED", "The company is not suspended.");
        company.restore();
        return view(company);
    }
    private Company lock(UUID companyId) {
        return companies.findByIdForUpdate(companyId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found."));
    }
    private static CompanyAdminViews.Company view(Company company) {
        return new CompanyAdminViews.Company(company.getId(), company.getName(), company.getCreatedAt(), company.isPublicSeller(), company.isSuspended());
    }
    private static CompanyAdminViews.Membership membership(Membership m) {
        return new CompanyAdminViews.Membership(m.getCompany().getId(), m.getCompany().getName(), m.getUserId(), m.getRole());
    }
}

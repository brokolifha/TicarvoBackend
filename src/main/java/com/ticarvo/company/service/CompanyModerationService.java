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
    private final boolean verificationRequired;
    private final com.ticarvo.notification.service.Notifications notifications;
    public CompanyModerationService(CompanyRepository companies, MembershipRepository memberships, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${ticarvo.sellers.verification-required:true}") boolean verificationRequired,
            com.ticarvo.notification.service.Notifications notifications) {
        this.companies = companies; this.memberships = memberships; this.clock = clock; this.verificationRequired = verificationRequired;
        this.notifications = notifications;
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
    @Transactional(readOnly = true)
    public Page<com.ticarvo.company.dto.ProfileDtos.Profile> sellerApplications(int page, int size) {
        return companies.findBySellerStatus(com.ticarvo.company.model.SellerStatus.PENDING,
            PageRequest.of(page, size, Sort.by("sellerAppliedAt").and(Sort.by("id")))).map(CompanyProfileService::profile);
    }
    @Transactional(readOnly = true)
    public Optional<com.ticarvo.company.dto.ProfileDtos.Profile> profile(UUID companyId) { return companies.findById(companyId).map(CompanyProfileService::profile); }
    @Transactional(propagation = Propagation.MANDATORY)
    public com.ticarvo.company.dto.ProfileDtos.Profile decideSeller(UUID companyId, com.ticarvo.company.model.SellerStatus outcome, String note) {
        boolean verify = outcome == com.ticarvo.company.model.SellerStatus.VERIFIED;
        if (!verify && outcome != com.ticarvo.company.model.SellerStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The outcome must be VERIFIED or REJECTED.");
        }
        String reason = CompanyProfileService.clean(note);
        if (!verify && reason == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "A rejection needs a reason.");
        var company = lock(companyId);
        var current = company.getSellerStatus();
        boolean allowed = current == com.ticarvo.company.model.SellerStatus.PENDING || (!verify && current == com.ticarvo.company.model.SellerStatus.VERIFIED);
        if (!allowed) throw new ApiException(HttpStatus.CONFLICT, "SELLER_STATUS_CONFLICT", "The company has no application in a state this decision applies to.");
        // Without the verification requirement (local development) every company may sell anyway.
        company.seller(outcome, verify ? null : reason, company.getSellerAppliedAt(), verify || !verificationRequired);
        notifications.notify(memberships.findByCompanyIdOrderByCreatedAtAscIdAsc(companyId).stream().map(m -> m.getUserId()).toList(), companyId,
            verify ? "Satıcı başvurunuz onaylandı; ürünleriniz satışa açık." : "Satıcı başvurunuz kabul edilmedi. Gerekçe firma profilinde.", "/panel/firma/profil");
        return CompanyProfileService.profile(company);
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

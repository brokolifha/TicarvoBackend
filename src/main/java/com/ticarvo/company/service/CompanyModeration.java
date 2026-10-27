package com.ticarvo.company.service;

import com.ticarvo.company.dto.CompanyAdminViews;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** Company boundary for the admin module (ADR-008). */
public interface CompanyModeration {
    Page<CompanyAdminViews.Company> search(String likePattern, int page, int size);
    Optional<CompanyAdminViews.Company> find(UUID companyId);
    List<CompanyAdminViews.Membership> members(UUID companyId);
    List<CompanyAdminViews.Membership> companiesOf(UUID userId);
    /** OWNER and ADMIN user ids: the people told about a restriction. */
    List<UUID> managerIds(UUID companyId);
    /** Must run inside the caller's transaction and takes the company row lock; 404 unknown, 409 if already in that state. */
    CompanyAdminViews.Company suspend(UUID companyId);
    CompanyAdminViews.Company restore(UUID companyId);
    /** ADR-011 seller applications, oldest first. */
    Page<com.ticarvo.company.dto.ProfileDtos.Profile> sellerApplications(int page, int size);
    Optional<com.ticarvo.company.dto.ProfileDtos.Profile> profile(UUID companyId);
    /** Inside the caller's transaction, under the company row lock: PENDING to VERIFIED or REJECTED, or VERIFIED to REJECTED (revoke). */
    com.ticarvo.company.dto.ProfileDtos.Profile decideSeller(UUID companyId, com.ticarvo.company.model.SellerStatus outcome, String note);
}

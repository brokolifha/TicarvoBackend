package com.ticarvo.company.service;

import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.identity.service.AccountDeletionGuard;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * ADR-013: an owner of a company with other members hands it over first. A sole owner's company stays for its records
 * (orders are kept by law) but leaves the directory and stops selling, since nobody is left to serve its buyers.
 */
@Component
public class CompanyAccountDeletion implements AccountDeletionGuard {
    private final MembershipRepository memberships;
    private final CompanyRepository companies;
    public CompanyAccountDeletion(MembershipRepository memberships, CompanyRepository companies) {
        this.memberships = memberships; this.companies = companies;
    }
    @Override public void beforeDeletion(UUID userId) {
        var mine = memberships.findByUserId(userId, Pageable.unpaged()).getContent();
        for (var m : mine) {
            if ("OWNER".equals(m.getRole()) && memberships.countByCompanyId(m.getCompany().getId()) > 1) {
                throw new ApiException(HttpStatus.CONFLICT, "OWNER_MUST_TRANSFER", "Transfer ownership of your companies before deleting the account.");
            }
        }
        for (var m : mine) {
            if ("OWNER".equals(m.getRole())) {
                var company = companies.findById(m.getCompany().getId()).orElseThrow();
                company.setPublicSeller(false);
                company.seller(company.getSellerStatus(), company.getSellerNote(), company.getSellerAppliedAt(), false);
            }
        }
        memberships.deleteAll(mine);
    }
}

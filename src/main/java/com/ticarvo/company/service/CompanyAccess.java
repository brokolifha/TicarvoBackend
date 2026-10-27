package com.ticarvo.company.service;
import com.ticarvo.company.dto.CompanySummary;
import java.util.UUID;
import org.springframework.security.core.Authentication;
public interface CompanyAccess {
 /** Role order OWNER > ADMIN > MEMBER (ADR-006); a non-member always gets 404. Each returns the acting user id. */
 UUID requireOwner(UUID companyId, Authentication authentication);
 UUID requireAdmin(UUID companyId, Authentication authentication);
 UUID requireMember(UUID companyId, Authentication authentication);
 CompanySummary lockSeller(UUID sellerId);
 /** Shared (FOR SHARE) lock: concurrent readers proceed, a writer holding lockSeller is still awaited. */
 CompanySummary lockSellerForRead(UUID sellerId);
 /** Locks several companies in UUID order, so two such callers can never deadlock (ADR-009); unknown ids are absent. */
 java.util.Map<UUID,CompanySummary> lockCompanies(java.util.Collection<UUID> companyIds);
 CompanySummary summary(UUID companyId);
 java.util.Map<UUID,CompanySummary> summaries(java.util.Collection<UUID> ids);
}

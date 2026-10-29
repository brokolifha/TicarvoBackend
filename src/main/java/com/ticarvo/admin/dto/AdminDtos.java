package com.ticarvo.admin.dto;

import com.ticarvo.admin.model.Moderation.*;
import com.ticarvo.company.dto.CompanyAdminViews;
import com.ticarvo.identity.dto.AccountView;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}
    public record Page<T>(List<T> content, int page, int size, long totalElements) {}

    public record NewComplaint(@NotNull UUID companyId, UUID productId, @NotNull PolicyReason reason,
        @NotBlank @Size(min = 20, max = 2000) String description) {}
    /** ADR-013: a notice from anyone, without an account; goodFaith is the DSA Art. 16(2)(d) statement. */
    public record NewNotice(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 254) String email,
        @NotNull UUID companyId, UUID productId, @NotNull PolicyReason reason,
        @NotBlank @Size(min = 20, max = 2000) String description, @AssertTrue boolean goodFaith) {}
    public record NoticeConfirm(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "NoticeConfirm[REDACTED]"; }
    }
    /** The reporter's view: never the admin note or who decided. */
    public record MyComplaint(UUID id, UUID companyId, UUID productId, PolicyReason reason, String description,
        ComplaintStatus status, Instant createdAt, Instant decidedAt) {}
    /** reporterUserId is null and reporterName set for an anonymous notice (ADR-013). */
    public record AdminComplaint(UUID id, UUID reporterUserId, String reporterName, String reporterEmail, UUID companyId, String companyName, UUID productId,
        PolicyReason reason, String description, ComplaintStatus status, String adminNote, UUID reviewerUserId,
        Instant createdAt, Instant decidedAt, long version) {}
    public record ExpectedVersion(@NotNull @Min(0) Long expectedVersion) {}
    public record ResolveComplaint(@NotNull @Min(0) Long expectedVersion, @NotNull ComplaintStatus outcome,
        @NotBlank @Size(min = 10, max = 2000) String note) {}

    public record Suspend(@NotNull PolicyReason reason, @NotBlank @Size(min = 20, max = 2000) String statement, UUID complaintId) {}
    public record Restore(@NotBlank @Size(min = 20, max = 2000) String statement) {}
    public record RestrictionView(UUID id, TargetType targetType, UUID targetId, RestrictionAction action, PolicyReason reason,
        String statement, UUID complaintId, Instant createdAt) {}

    public record NewAppeal(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token, @NotBlank @Size(min = 20, max = 2000) String text) {
        @Override public String toString() { return "NewAppeal[REDACTED]"; }
    }
    public record AppealReceipt(UUID id, AppealStatus status) {}
    public record AdminAppeal(UUID id, RestrictionView restriction, String text, AppealStatus status, String decisionNote,
        Instant createdAt, Instant decidedAt, long version) {}
    public record DecideAppeal(@NotNull @Min(0) Long expectedVersion, @NotNull AppealStatus outcome,
        @NotBlank @Size(min = 20, max = 2000) String note) {}

    public record UserDetail(AccountView account, boolean platformAdmin, List<CompanyAdminViews.Membership> companies,
        List<RestrictionView> restrictions) {}
    public record Member(UUID userId, String email, String displayName, String role) {}
    /** ADR-011; a rejection (or revoking a verification) needs a note, which the company sees. */
    public record SellerDecision(@NotNull com.ticarvo.company.model.SellerStatus outcome, @Size(max = 500) String note) {}
    public record CompanyDetail(CompanyAdminViews.Company company, List<Member> members, List<RestrictionView> restrictions) {}
}

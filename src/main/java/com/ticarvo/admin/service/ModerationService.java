package com.ticarvo.admin.service;

import com.ticarvo.admin.dto.AdminDtos;
import com.ticarvo.admin.entity.Appeal;
import com.ticarvo.admin.entity.Restriction;
import com.ticarvo.admin.model.Moderation.*;
import com.ticarvo.admin.repository.AppealRepository;
import com.ticarvo.admin.repository.RestrictionRepository;
import com.ticarvo.company.dto.CompanyAdminViews;
import com.ticarvo.company.service.CompanyModeration;
import com.ticarvo.identity.dto.AccountView;
import com.ticarvo.identity.service.AccountModeration;
import com.ticarvo.identity.service.UserDirectory;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.mail.MailOutbox;
import com.ticarvo.shared.security.SecureTokens;
import com.ticarvo.shared.validation.PageValidation;
import com.ticarvo.shared.validation.SearchText;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Suspensions with a statement of reasons, appeals, and the admin's minimal view of users and companies (ADR-008). */
@Service
public class ModerationService {
    /** The DSA asks for at least six months. */
    static final Duration APPEAL_WINDOW = Duration.ofDays(180);
    private final RestrictionRepository restrictions;
    private final AppealRepository appeals;
    private final AccountModeration accounts;
    private final CompanyModeration companies;
    private final UserDirectory directory;
    private final ComplaintService complaints;
    private final AdminAccess admin;
    private final MailOutbox mail;
    private final Clock clock;
    public ModerationService(RestrictionRepository restrictions, AppealRepository appeals, AccountModeration accounts,
            CompanyModeration companies, UserDirectory directory, ComplaintService complaints, AdminAccess admin, MailOutbox mail, Clock clock) {
        this.restrictions = restrictions; this.appeals = appeals; this.accounts = accounts; this.companies = companies;
        this.directory = directory; this.complaints = complaints; this.admin = admin; this.mail = mail; this.clock = clock;
    }

    @Transactional
    public AdminDtos.RestrictionView suspendUser(UUID userId, AdminDtos.Suspend request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        if (admin.isAdmin(userId)) throw new ApiException(HttpStatus.CONFLICT, "TARGET_IS_ADMIN", "Revoke platform admin permission first.");
        if (request.complaintId() != null) complaints.requireExists(request.complaintId());
        var account = accounts.suspend(userId);
        return suspended(TargetType.USER, userId, request, actor, List.of(account.email()));
    }
    @Transactional
    public AdminDtos.RestrictionView suspendCompany(UUID companyId, AdminDtos.Suspend request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        if (request.complaintId() != null) complaints.requireExists(request.complaintId());
        companies.suspend(companyId);
        var emails = directory.summaries(companies.managerIds(companyId)).values().stream().map(u -> u.email()).toList();
        return suspended(TargetType.COMPANY, companyId, request, actor, emails);
    }
    @Transactional
    public AdminDtos.RestrictionView restoreUser(UUID userId, AdminDtos.Restore request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        accounts.restore(userId);
        return restored(TargetType.USER, userId, request.statement(), actor);
    }
    @Transactional
    public AdminDtos.RestrictionView restoreCompany(UUID companyId, AdminDtos.Restore request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        companies.restore(companyId);
        return restored(TargetType.COMPANY, companyId, request.statement(), actor);
    }

    /** Anonymous: a suspended user cannot log in, so the mailed single-use code is the proof. */
    @Transactional
    public AdminDtos.AppealReceipt appeal(AdminDtos.NewAppeal request) {
        var now = clock.instant();
        var restriction = restrictions.lockByAppealTokenHash(SecureTokens.sha256Hex(request.token()))
            .filter(r -> r.appealOpen(now) && isCurrent(r))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "The appeal code is invalid or expired."));
        restriction.spendAppealCode();
        var appeal = appeals.save(new Appeal(restriction.id(), request.text().strip(), now));
        return new AdminDtos.AppealReceipt(appeal.id(), appeal.status());
    }
    @Transactional(readOnly = true)
    public AdminDtos.Page<AdminDtos.AdminAppeal> appealQueue(AppealStatus status, int page, int size, Authentication auth) {
        admin.requireAdmin(auth);
        PageValidation.validatePage(page, size);
        var request = PageRequest.of(page, size, Sort.by("createdAt").and(Sort.by("id")));
        var result = status == null ? appeals.findAll(request) : appeals.findByStatus(status, request);
        return new AdminDtos.Page<>(result.map(this::view).getContent(), page, size, result.getTotalElements());
    }
    @Transactional
    public AdminDtos.AdminAppeal decide(UUID id, AdminDtos.DecideAppeal request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        if (request.outcome() == AppealStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Outcome must be UPHELD or REVERSED.");
        }
        var appeal = appeals.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPEAL_NOT_FOUND", "Appeal was not found."));
        if (appeal.version() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The appeal changed. Reload before retrying.");
        }
        if (appeal.status() != AppealStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "APPEAL_STATE_CONFLICT", "The appeal is already decided.");
        }
        String note = request.note().strip();
        appeal.decide(actor, request.outcome(), note, clock.instant());
        admin.record(actor, "APPEAL_" + request.outcome().name(), "APPEAL", id);
        var restriction = restrictions.findById(appeal.restrictionId()).orElseThrow();
        // Reversal lifts the restriction unless an admin already did.
        if (request.outcome() == AppealStatus.REVERSED && isCurrent(restriction)) {
            if (restriction.targetType() == TargetType.USER) accounts.restore(restriction.targetId());
            else companies.restore(restriction.targetId());
            restored(restriction.targetType(), restriction.targetId(), note, actor);
        }
        return view(appeals.saveAndFlush(appeal));
    }

    @Transactional(readOnly = true)
    public AdminDtos.Page<AccountView> users(String q, int page, int size, Authentication auth) {
        admin.requireAdmin(auth);
        PageValidation.validatePage(page, size);
        var result = accounts.search(SearchText.likePattern(q), page, size);
        return new AdminDtos.Page<>(result.getContent(), page, size, result.getTotalElements());
    }
    @Transactional(readOnly = true)
    public AdminDtos.UserDetail user(UUID userId, Authentication auth) {
        admin.requireAdmin(auth);
        var account = accounts.find(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found."));
        return new AdminDtos.UserDetail(account, admin.isAdmin(userId), companies.companiesOf(userId), history(TargetType.USER, userId));
    }
    @Transactional(readOnly = true)
    public AdminDtos.Page<CompanyAdminViews.Company> companies(String q, int page, int size, Authentication auth) {
        admin.requireAdmin(auth);
        PageValidation.validatePage(page, size);
        var result = companies.search(SearchText.likePattern(q), page, size);
        return new AdminDtos.Page<>(result.getContent(), page, size, result.getTotalElements());
    }
    @Transactional(readOnly = true)
    public AdminDtos.CompanyDetail company(UUID companyId, Authentication auth) {
        admin.requireAdmin(auth);
        var company = companies.find(companyId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found."));
        var members = companies.members(companyId);
        var profiles = directory.summaries(members.stream().map(m -> m.userId()).toList());
        return new AdminDtos.CompanyDetail(company, members.stream().map(m -> new AdminDtos.Member(m.userId(),
            profiles.get(m.userId()).email(), profiles.get(m.userId()).displayName(), m.role())).toList(), history(TargetType.COMPANY, companyId));
    }

    private AdminDtos.RestrictionView suspended(TargetType type, UUID target, AdminDtos.Suspend request, UUID actor, List<String> recipients) {
        var now = clock.instant();
        String code = SecureTokens.generate();
        var restriction = restrictions.save(new Restriction(type, target, RestrictionAction.SUSPEND, request.reason(), request.statement().strip(),
            request.complaintId(), actor, now, SecureTokens.sha256Hex(code), now.plus(APPEAL_WINDOW)));
        admin.record(actor, type + "_SUSPENDED", type.name(), target);
        // Statement of reasons (DSA Art. 17) only once the decision is committed.
        String subject = type == TargetType.USER ? "Ticarvo hesabınız askıya alındı" : "Ticarvo firmanız askıya alındı";
        String body = "Gerekçe: " + restriction.reason() + "\nAçıklama: " + restriction.statement()
            + "\n\nBu karara " + restriction.appealUntil() + " tarihine kadar bir kez itiraz edebilirsiniz."
            + "\nİtiraz için POST /api/v1/moderation/appeals adresine bu kodu ve itiraz metninizi gönderin:\n" + code;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { recipients.forEach(to -> mail.send(to, subject, body)); }
        });
        return view(restriction);
    }
    private AdminDtos.RestrictionView restored(TargetType type, UUID target, String statement, UUID actor) {
        var restriction = restrictions.save(new Restriction(type, target, RestrictionAction.RESTORE, null, statement.strip(),
            null, actor, clock.instant(), null, null));
        admin.record(actor, type + "_RESTORED", type.name(), target);
        return view(restriction);
    }
    /** An appeal only makes sense against the decision that is still in force. */
    private boolean isCurrent(Restriction restriction) {
        var latest = restrictions.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(restriction.targetType(), restriction.targetId());
        return !latest.isEmpty() && latest.getFirst().id().equals(restriction.id());
    }
    private List<AdminDtos.RestrictionView> history(TargetType type, UUID target) {
        return restrictions.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(type, target).stream().map(ModerationService::view).toList();
    }
    private AdminDtos.AdminAppeal view(Appeal a) {
        return new AdminDtos.AdminAppeal(a.id(), view(restrictions.findById(a.restrictionId()).orElseThrow()), a.text(), a.status(),
            a.decisionNote(), a.createdAt(), a.decidedAt(), a.version());
    }
    private static AdminDtos.RestrictionView view(Restriction r) {
        return new AdminDtos.RestrictionView(r.id(), r.targetType(), r.targetId(), r.action(), r.reason(), r.statement(), r.complaintId(), r.createdAt());
    }
}

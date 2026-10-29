package com.ticarvo.admin.service;

import com.ticarvo.admin.dto.AdminDtos;
import com.ticarvo.admin.entity.Complaint;
import com.ticarvo.admin.entity.PendingNotice;
import com.ticarvo.admin.model.Moderation.ComplaintStatus;
import com.ticarvo.admin.repository.ComplaintRepository;
import com.ticarvo.admin.repository.PendingNoticeRepository;
import com.ticarvo.catalog.service.CatalogLookup;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.company.service.CompanyModeration;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.identity.service.UserDirectory;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.mail.MailOutbox;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.shared.security.SecureTokens;
import com.ticarvo.shared.validation.PageValidation;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Complaints (ADR-008): reporters see only their own; a complaint never restricts anyone by itself. */
@Service
public class ComplaintService {
    static final int MAX_OPEN_PER_REPORTER = 20;
    private static final List<ComplaintStatus> OPEN = List.of(ComplaintStatus.OPEN, ComplaintStatus.IN_REVIEW);
    private final ComplaintRepository complaints;
    private final CurrentUserProvider users;
    private final UserDirectory directory;
    private final CompanyModeration companyModeration;
    private final CompanyAccess companies;
    private final CatalogLookup catalog;
    private final AdminAccess admin;
    private final PrincipalRateLimit limits;
    private final PendingNoticeRepository notices;
    private final MailOutbox mail;
    private final Clock clock;
    static final java.time.Duration NOTICE_TTL = java.time.Duration.ofHours(24);
    static final int NOTICES_PER_EMAIL_PER_HOUR = 3;
    public ComplaintService(ComplaintRepository complaints, CurrentUserProvider users, UserDirectory directory, CompanyModeration companyModeration,
            CompanyAccess companies, CatalogLookup catalog, AdminAccess admin, PrincipalRateLimit limits,
            PendingNoticeRepository notices, MailOutbox mail, Clock clock) {
        this.complaints = complaints; this.users = users; this.directory = directory; this.companyModeration = companyModeration;
        this.companies = companies; this.catalog = catalog; this.admin = admin; this.limits = limits;
        this.notices = notices; this.mail = mail; this.clock = clock;
    }

    // shortcut: the open-count and duplicate checks are not serialized per reporter; two parallel requests can both pass once.
    @Transactional
    public AdminDtos.MyComplaint create(AdminDtos.NewComplaint request, Authentication auth) {
        UUID reporter = users.currentUserId(auth);
        limits.check(PrincipalRateLimit.Operation.COMPLAINT_CREATE, reporter);
        requireTarget(request.companyId(), request.productId());
        if (complaints.countByReporterUserIdAndStatusIn(reporter, OPEN) >= MAX_OPEN_PER_REPORTER) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPLAINT_LIMIT_REACHED", "Too many open complaints. Wait for a decision first.");
        }
        if (complaints.existsSameTarget(reporter, request.companyId(), request.productId(), OPEN)) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPLAINT_DUPLICATE", "You already have an open complaint about this.");
        }
        return mine(complaints.save(new Complaint(reporter, request.companyId(), request.productId(), request.reason(),
            request.description().strip(), clock.instant())));
    }
    /**
     * ADR-013: a notice without an account waits for its sender to confirm the mailed code. Always the same answer
     * once the target exists, so the endpoint does not tell whether a mail was sent; a busy address is skipped quietly.
     */
    @Transactional
    public void notice(AdminDtos.NewNotice request) {
        requireTarget(request.companyId(), request.productId());
        var now = clock.instant();
        notices.deleteExpired(now);
        String email = request.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (notices.countByReporterEmailAndCreatedAtAfter(email, now.minus(java.time.Duration.ofHours(1))) >= NOTICES_PER_EMAIL_PER_HOUR) return;
        String token = SecureTokens.generate();
        notices.saveAndFlush(new PendingNotice(request.name().strip(), email, request.companyId(), request.productId(), request.reason(),
            request.description().strip(), SecureTokens.sha256Hex(token), now, now.plus(NOTICE_TTL)));
        afterCommit(() -> mail.send(email, "Ticarvo bildiriminizi onaylayın",
            "Bildiriminizi göndermek için " + mail.page("/bildirim-onayla") + " sayfasında bu kodu girin (24 saat geçerli). Siz göndermediyseniz yok sayın:\n" + token));
    }
    /** The confirmed notice becomes a complaint in the admin queue; the sender gets a receipt (DSA Art. 16(4)). */
    @Transactional
    public AdminDtos.MyComplaint confirmNotice(String token) {
        var pending = notices.findByTokenHash(SecureTokens.sha256Hex(token))
            .filter(n -> n.expiresAt().isAfter(clock.instant()))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "The code is invalid or expired."));
        notices.delete(pending);
        if (complaints.existsSameAnonymousTarget(pending.reporterEmail(), pending.companyId(), pending.productId(), OPEN)) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPLAINT_DUPLICATE", "You already have an open complaint about this.");
        }
        var complaint = complaints.save(Complaint.anonymous(pending.reporterName(), pending.reporterEmail(), pending.companyId(),
            pending.productId(), pending.reason(), pending.description(), clock.instant()));
        String email = pending.reporterEmail();
        afterCommit(() -> mail.send(email, "Ticarvo bildiriminiz alındı",
            "Bildiriminiz alındı ve platform ekibinin incelemesine sunuldu. Karar verildiğinde size bu adresten bilgi vereceğiz.\nBildirim no: " + complaint.id()));
        return mine(complaint);
    }
    private void requireTarget(UUID companyId, UUID productId) {
        if (companyModeration.find(companyId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found.");
        }
        if (productId != null && !catalog.sellerOf(productId).map(companyId::equals).orElse(false)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product was not found for this company.");
        }
    }
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    @Transactional(readOnly = true)
    public AdminDtos.Page<AdminDtos.MyComplaint> mine(int page, int size, Authentication auth) {
        PageValidation.validatePage(page, size);
        var result = complaints.findByReporterUserId(users.currentUserId(auth), PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new AdminDtos.Page<>(result.map(ComplaintService::mine).getContent(), page, size, result.getTotalElements());
    }
    @Transactional(readOnly = true)
    public AdminDtos.MyComplaint mine(UUID id, Authentication auth) {
        return mine(complaints.findByIdAndReporterUserId(id, users.currentUserId(auth)).orElseThrow(ComplaintService::notFound));
    }

    @Transactional(readOnly = true)
    public AdminDtos.Page<AdminDtos.AdminComplaint> queue(ComplaintStatus status, int page, int size, Authentication auth) {
        admin.requireAdmin(auth);
        PageValidation.validatePage(page, size);
        // Oldest first: the queue is worked in arrival order.
        var request = PageRequest.of(page, size, Sort.by("createdAt").and(Sort.by("id")));
        var result = status == null ? complaints.findAll(request) : complaints.findByStatus(status, request);
        return new AdminDtos.Page<>(adminViews(result.getContent()), page, size, result.getTotalElements());
    }
    @Transactional(readOnly = true)
    public AdminDtos.AdminComplaint get(UUID id, Authentication auth) {
        admin.requireAdmin(auth);
        return adminViews(List.of(complaints.findById(id).orElseThrow(ComplaintService::notFound))).getFirst();
    }
    @Transactional
    public AdminDtos.AdminComplaint review(UUID id, long expectedVersion, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        var complaint = locked(id, expectedVersion);
        if (complaint.status() != ComplaintStatus.OPEN) throw stateConflict();
        complaint.startReview(actor);
        admin.record(actor, "COMPLAINT_REVIEW_STARTED", "COMPLAINT", id);
        return adminViews(List.of(complaints.saveAndFlush(complaint))).getFirst();
    }
    @Transactional
    public AdminDtos.AdminComplaint resolve(UUID id, AdminDtos.ResolveComplaint request, Authentication auth) {
        UUID actor = admin.requireAdmin(auth);
        if (request.outcome().open()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Outcome must be ACTION_TAKEN or DISMISSED.");
        }
        var complaint = locked(id, request.expectedVersion());
        if (!complaint.status().open()) throw stateConflict();
        complaint.resolve(actor, request.outcome(), request.note().strip(), clock.instant());
        admin.record(actor, "COMPLAINT_" + request.outcome().name(), "COMPLAINT", id);
        // ADR-013: an anonymous sender has no account to look at, so the outcome goes by mail (DSA Art. 16(5)); never the internal note.
        if (complaint.reporterUserId() == null) {
            String to = complaint.reporterEmail();
            String outcome = request.outcome() == ComplaintStatus.ACTION_TAKEN ? "bildirdiğiniz içerikle ilgili işlem yapıldı" : "incelemede işlem gerektiren bir durum bulunmadı";
            afterCommit(() -> mail.send(to, "Ticarvo bildiriminiz sonuçlandı", "Bildiriminiz incelendi: " + outcome + ".\nBildirim no: " + id));
        }
        return adminViews(List.of(complaints.saveAndFlush(complaint))).getFirst();
    }
    /** For linking a restriction to a complaint. */
    void requireExists(UUID id) { if (!complaints.existsById(id)) throw notFound(); }

    private Complaint locked(UUID id, long expectedVersion) {
        var complaint = complaints.lockById(id).orElseThrow(ComplaintService::notFound);
        if (complaint.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The complaint changed. Reload before retrying.");
        }
        return complaint;
    }
    private List<AdminDtos.AdminComplaint> adminViews(List<Complaint> list) {
        if (list.isEmpty()) return List.of();
        var reporters = directory.summaries(list.stream().map(Complaint::reporterUserId).filter(java.util.Objects::nonNull).distinct().toList());
        var names = companies.summaries(list.stream().map(Complaint::companyId).distinct().toList());
        return list.stream().map(c -> new AdminDtos.AdminComplaint(c.id(), c.reporterUserId(), c.reporterName(),
            c.reporterUserId() == null ? c.reporterEmail() : reporters.get(c.reporterUserId()).email(),
            c.companyId(), names.get(c.companyId()).name(), c.productId(), c.reason(), c.description(), c.status(), c.adminNote(),
            c.reviewerUserId(), c.createdAt(), c.decidedAt(), c.version())).toList();
    }
    private static AdminDtos.MyComplaint mine(Complaint c) {
        return new AdminDtos.MyComplaint(c.id(), c.companyId(), c.productId(), c.reason(), c.description(), c.status(), c.createdAt(), c.decidedAt());
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "COMPLAINT_NOT_FOUND", "Complaint was not found."); }
    private static ApiException stateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "COMPLAINT_STATE_CONFLICT", "The complaint is not in a state that allows this.");
    }
}

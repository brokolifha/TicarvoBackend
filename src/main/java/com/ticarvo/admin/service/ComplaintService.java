package com.ticarvo.admin.service;

import com.ticarvo.admin.dto.AdminDtos;
import com.ticarvo.admin.entity.Complaint;
import com.ticarvo.admin.model.Moderation.ComplaintStatus;
import com.ticarvo.admin.repository.ComplaintRepository;
import com.ticarvo.catalog.service.CatalogLookup;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.company.service.CompanyModeration;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.identity.service.UserDirectory;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.shared.validation.PageValidation;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final Clock clock;
    public ComplaintService(ComplaintRepository complaints, CurrentUserProvider users, UserDirectory directory, CompanyModeration companyModeration,
            CompanyAccess companies, CatalogLookup catalog, AdminAccess admin, PrincipalRateLimit limits, Clock clock) {
        this.complaints = complaints; this.users = users; this.directory = directory; this.companyModeration = companyModeration;
        this.companies = companies; this.catalog = catalog; this.admin = admin; this.limits = limits; this.clock = clock;
    }

    // shortcut: the open-count and duplicate checks are not serialized per reporter; two parallel requests can both pass once.
    @Transactional
    public AdminDtos.MyComplaint create(AdminDtos.NewComplaint request, Authentication auth) {
        UUID reporter = users.currentUserId(auth);
        limits.check(PrincipalRateLimit.Operation.COMPLAINT_CREATE, reporter);
        if (companyModeration.find(request.companyId()).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found.");
        }
        if (request.productId() != null && !catalog.sellerOf(request.productId()).map(request.companyId()::equals).orElse(false)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product was not found for this company.");
        }
        if (complaints.countByReporterUserIdAndStatusIn(reporter, OPEN) >= MAX_OPEN_PER_REPORTER) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPLAINT_LIMIT_REACHED", "Too many open complaints. Wait for a decision first.");
        }
        if (complaints.existsSameTarget(reporter, request.companyId(), request.productId(), OPEN)) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPLAINT_DUPLICATE", "You already have an open complaint about this.");
        }
        return mine(complaints.save(new Complaint(reporter, request.companyId(), request.productId(), request.reason(),
            request.description().strip(), clock.instant())));
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
        var reporters = directory.summaries(list.stream().map(Complaint::reporterUserId).distinct().toList());
        var names = companies.summaries(list.stream().map(Complaint::companyId).distinct().toList());
        return list.stream().map(c -> new AdminDtos.AdminComplaint(c.id(), c.reporterUserId(), reporters.get(c.reporterUserId()).email(),
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

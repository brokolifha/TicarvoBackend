package com.ticarvo.admin.controller;
import com.ticarvo.admin.dto.AdminDtos;
import com.ticarvo.admin.model.Moderation.AppealStatus;
import com.ticarvo.admin.model.Moderation.ComplaintStatus;
import com.ticarvo.admin.service.ComplaintService;
import com.ticarvo.admin.service.ModerationService;
import com.ticarvo.company.dto.CompanyAdminViews;
import com.ticarvo.identity.dto.AccountView;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Platform admin API (ADR-008); every operation checks admin permission and an MFA session in the service. */
@RestController @RequestMapping("/api/v1/admin")
public class AdminController {
    private final ComplaintService complaints;
    private final ModerationService moderation;
    public AdminController(ComplaintService complaints, ModerationService moderation) { this.complaints = complaints; this.moderation = moderation; }

    @GetMapping("/users")
    public AdminDtos.Page<AccountView> users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) { return moderation.users(q, page, size, auth); }
    @GetMapping("/users/{id}")
    public AdminDtos.UserDetail user(@PathVariable UUID id, Authentication auth) { return moderation.user(id, auth); }
    @PostMapping("/users/{id}/suspension")
    public AdminDtos.RestrictionView suspendUser(@PathVariable UUID id, @Valid @RequestBody AdminDtos.Suspend request, Authentication auth) {
        return moderation.suspendUser(id, request, auth);
    }
    @PostMapping("/users/{id}/restore")
    public AdminDtos.RestrictionView restoreUser(@PathVariable UUID id, @Valid @RequestBody AdminDtos.Restore request, Authentication auth) {
        return moderation.restoreUser(id, request, auth);
    }

    @GetMapping("/companies")
    public AdminDtos.Page<CompanyAdminViews.Company> companies(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) { return moderation.companies(q, page, size, auth); }
    @GetMapping("/companies/{id}")
    public AdminDtos.CompanyDetail company(@PathVariable UUID id, Authentication auth) { return moderation.company(id, auth); }
    @PostMapping("/companies/{id}/suspension")
    public AdminDtos.RestrictionView suspendCompany(@PathVariable UUID id, @Valid @RequestBody AdminDtos.Suspend request, Authentication auth) {
        return moderation.suspendCompany(id, request, auth);
    }
    @PostMapping("/companies/{id}/restore")
    public AdminDtos.RestrictionView restoreCompany(@PathVariable UUID id, @Valid @RequestBody AdminDtos.Restore request, Authentication auth) {
        return moderation.restoreCompany(id, request, auth);
    }

    @GetMapping("/complaints")
    public AdminDtos.Page<AdminDtos.AdminComplaint> complaints(@RequestParam(required = false) ComplaintStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return complaints.queue(status, page, size, auth);
    }
    @GetMapping("/complaints/{id}")
    public AdminDtos.AdminComplaint complaint(@PathVariable UUID id, Authentication auth) { return complaints.get(id, auth); }
    @PostMapping("/complaints/{id}/review")
    public AdminDtos.AdminComplaint review(@PathVariable UUID id, @Valid @RequestBody AdminDtos.ExpectedVersion request, Authentication auth) {
        return complaints.review(id, request.expectedVersion(), auth);
    }
    @PostMapping("/complaints/{id}/resolve")
    public AdminDtos.AdminComplaint resolve(@PathVariable UUID id, @Valid @RequestBody AdminDtos.ResolveComplaint request, Authentication auth) {
        return complaints.resolve(id, request, auth);
    }

    @GetMapping("/appeals")
    public AdminDtos.Page<AdminDtos.AdminAppeal> appeals(@RequestParam(required = false) AppealStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return moderation.appealQueue(status, page, size, auth);
    }
    @PostMapping("/appeals/{id}/decide")
    public AdminDtos.AdminAppeal decide(@PathVariable UUID id, @Valid @RequestBody AdminDtos.DecideAppeal request, Authentication auth) {
        return moderation.decide(id, request, auth);
    }
}

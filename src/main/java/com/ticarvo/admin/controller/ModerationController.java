package com.ticarvo.admin.controller;
import com.ticarvo.admin.dto.AdminDtos;
import com.ticarvo.admin.service.ComplaintService;
import com.ticarvo.admin.service.ModerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** The user side of ADR-008: own complaints and the code-based appeal. */
@RestController @RequestMapping("/api/v1")
public class ModerationController {
    private final ComplaintService complaints;
    private final ModerationService moderation;
    public ModerationController(ComplaintService complaints, ModerationService moderation) { this.complaints = complaints; this.moderation = moderation; }
    @PostMapping("/complaints") @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.MyComplaint create(@Valid @RequestBody AdminDtos.NewComplaint request, Authentication auth) { return complaints.create(request, auth); }
    /** ADR-013: anonymous notices, confirmed by a mailed code. */
    @PostMapping("/notices") @ResponseStatus(HttpStatus.ACCEPTED)
    public void notice(@Valid @RequestBody AdminDtos.NewNotice request) { complaints.notice(request); }
    @PostMapping("/notices/confirm") @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.MyComplaint confirmNotice(@Valid @RequestBody AdminDtos.NoticeConfirm request) { return complaints.confirmNotice(request.token()); }
    @GetMapping("/complaints")
    public AdminDtos.Page<AdminDtos.MyComplaint> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return complaints.mine(page, size, auth);
    }
    @GetMapping("/complaints/{id}")
    public AdminDtos.MyComplaint get(@PathVariable UUID id, Authentication auth) { return complaints.mine(id, auth); }
    @PostMapping("/moderation/appeals") @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.AppealReceipt appeal(@Valid @RequestBody AdminDtos.NewAppeal request) { return moderation.appeal(request); }
}

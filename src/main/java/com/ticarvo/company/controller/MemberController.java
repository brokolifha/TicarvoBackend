package com.ticarvo.company.controller;
import com.ticarvo.company.dto.MemberDtos;
import com.ticarvo.company.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class MemberController {
    private final MemberService members;
    public MemberController(MemberService members) { this.members = members; }
    @GetMapping("/companies/{companyId}/members")
    public List<MemberDtos.Member> list(@PathVariable UUID companyId, Authentication auth) { return members.members(companyId, auth); }
    @PostMapping("/companies/{companyId}/member-invitations") @ResponseStatus(HttpStatus.CREATED)
    public MemberDtos.Invitation invite(@PathVariable UUID companyId, @Valid @RequestBody MemberDtos.Invite request, Authentication auth) {
        return members.invite(companyId, request, auth);
    }
    @GetMapping("/companies/{companyId}/member-invitations")
    public List<MemberDtos.Invitation> invitations(@PathVariable UUID companyId, Authentication auth) { return members.invitations(companyId, auth); }
    @DeleteMapping("/companies/{companyId}/member-invitations/{invitationId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID companyId, @PathVariable UUID invitationId, Authentication auth) {
        members.revokeInvitation(companyId, invitationId, auth);
    }
    @PostMapping("/member-invitations/accept")
    public MemberDtos.Accepted accept(@Valid @RequestBody MemberDtos.Accept request, Authentication auth) { return members.accept(request.token(), auth); }
    @PutMapping("/companies/{companyId}/members/{userId}")
    public MemberDtos.Member changeRole(@PathVariable UUID companyId, @PathVariable UUID userId,
            @Valid @RequestBody MemberDtos.RoleChange request, Authentication auth) {
        return members.changeRole(companyId, userId, request.role(), auth);
    }
    @DeleteMapping("/companies/{companyId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID companyId, @PathVariable UUID userId, Authentication auth) { members.remove(companyId, userId, auth); }
    @PostMapping("/companies/{companyId}/ownership-transfer") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable UUID companyId, @Valid @RequestBody MemberDtos.Transfer request, Authentication auth) {
        members.transferOwnership(companyId, request.userId(), auth);
    }
}

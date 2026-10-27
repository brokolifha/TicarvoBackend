package com.ticarvo.company.service;
import com.ticarvo.company.dto.MemberDtos;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.entity.MemberInvitation;
import com.ticarvo.company.entity.Membership;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.company.repository.MemberInvitationRepository;
import com.ticarvo.company.repository.MembershipRepository;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.identity.service.UserDirectory;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.mail.MailOutbox;
import com.ticarvo.shared.security.BoundedRateLimiter;
import com.ticarvo.shared.security.SecureTokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import static com.ticarvo.company.service.CompanyAccessService.*;

/**
 * Company members, invitations and ownership (ADR-006).
 * Every change locks the company row and re-reads the actor's role under READ_COMMITTED, so concurrent
 * role changes, removals and ownership transfers of one company are serialized.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MemberService {
    static final int MAX_SEATS = 50;
    static final int INVITES_PER_WINDOW = 20;
    static final Duration INVITATION_TTL = Duration.ofDays(7);
    private final CompanyRepository companies;
    private final MembershipRepository memberships;
    private final MemberInvitationRepository invitations;
    private final CompanyAccess access;
    private final CurrentUserProvider identity;
    private final UserDirectory users;
    private final MailOutbox mail;
    private final Clock clock;
    private final BoundedRateLimiter inviteLimiter;
    public MemberService(CompanyRepository companies, MembershipRepository memberships, MemberInvitationRepository invitations,
            CompanyAccess access, CurrentUserProvider identity, UserDirectory users, MailOutbox mail, Clock clock) {
        this.companies = companies; this.memberships = memberships; this.invitations = invitations; this.access = access;
        this.identity = identity; this.users = users; this.mail = mail; this.clock = clock;
        this.inviteLimiter = new BoundedRateLimiter(clock, Duration.ofMinutes(15), 10000);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<MemberDtos.Member> members(UUID companyId, Authentication auth) {
        access.requireMember(companyId, auth);
        var list = memberships.findByCompanyIdOrderByCreatedAtAscIdAsc(companyId);
        var profiles = users.summaries(list.stream().map(Membership::getUserId).toList());
        return list.stream()
            .sorted(Comparator.comparingInt((Membership m) -> -rank(m.getRole())))
            .map(m -> new MemberDtos.Member(m.getUserId(), profiles.get(m.getUserId()).email(),
                profiles.get(m.getUserId()).displayName(), m.getRole(), m.getCreatedAt()))
            .toList();
    }
    public MemberDtos.Invitation invite(UUID companyId, MemberDtos.Invite request, Authentication auth) {
        UUID actor = access.requireAdmin(companyId, auth);
        if (!inviteLimiter.allow(actor.toString(), INVITES_PER_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later.");
        }
        var company = lock(companyId);
        String actorRole = requireAtLeast(companyId, actor, ADMIN);
        if (ADMIN.equals(request.role()) && !OWNER.equals(actorRole)) throw ownerRequired();
        String email = normalizeEmail(request.email());
        var current = memberships.findByCompanyIdOrderByCreatedAtAscIdAsc(companyId);
        boolean alreadyMember = users.summaries(current.stream().map(Membership::getUserId).toList()).values().stream()
            .anyMatch(user -> user.email().equals(email));
        if (alreadyMember) throw conflict("ALREADY_MEMBER", "This person is already a member.");
        invitations.deleteByCompanyAndEmail(companyId, email);
        if (current.size() + invitations.countByCompanyId(companyId) >= MAX_SEATS) {
            throw conflict("MEMBER_LIMIT_REACHED", "A company can have at most " + MAX_SEATS + " members and pending invitations.");
        }
        var now = clock.instant();
        String token = SecureTokens.generate();
        var invitation = invitations.saveAndFlush(new MemberInvitation(companyId, email, request.role(),
            SecureTokens.sha256Hex(token), actor, now, now.plus(INVITATION_TTL)));
        mail.send(email, "Ticarvo firma daveti", company.getName() + " firmasına " + request.role()
            + " olarak davet edildiniz. Ticarvo hesabınızla giriş yapıp bu kodla kabul edin (7 gün geçerli):\n" + token);
        return invitation(invitation);
    }
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<MemberDtos.Invitation> invitations(UUID companyId, Authentication auth) {
        access.requireAdmin(companyId, auth);
        return invitations.findByCompanyIdOrderByCreatedAtDescIdAsc(companyId).stream().map(MemberService::invitation).toList();
    }
    public void revokeInvitation(UUID companyId, UUID invitationId, Authentication auth) {
        UUID actor = access.requireAdmin(companyId, auth);
        lock(companyId);
        String actorRole = requireAtLeast(companyId, actor, ADMIN);
        var invitation = invitations.findByIdAndCompanyId(invitationId, companyId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation was not found."));
        if (ADMIN.equals(invitation.getRole()) && !OWNER.equals(actorRole)) throw ownerRequired();
        invitations.delete(invitation);
    }
    public MemberDtos.Accepted accept(String token, Authentication auth) {
        UUID user = identity.currentUserId(auth);
        String hash = SecureTokens.sha256Hex(token);
        // Lock order company -> invitation, the same as invite(), so the two cannot deadlock.
        var company = lock(invitations.companyIdForHash(hash).orElseThrow(MemberService::invitationNotFound));
        var invitation = invitations.findForUpdate(hash)
            .filter(candidate -> candidate.isValidAt(clock.instant()))
            // A code only works for the invited address, so it cannot be handed to another account.
            .filter(candidate -> candidate.getEmail().equals(normalizeEmail(auth.getName())))
            .orElseThrow(MemberService::invitationNotFound);
        if (memberships.findByCompanyIdAndUserId(company.getId(), user).isPresent()) {
            throw conflict("ALREADY_MEMBER", "You are already a member of this company.");
        }
        memberships.save(new Membership(company, user, invitation.getRole()));
        invitations.delete(invitation);
        return new MemberDtos.Accepted(company.getId(), invitation.getRole());
    }
    public MemberDtos.Member changeRole(UUID companyId, UUID userId, String role, Authentication auth) {
        UUID actor = access.requireOwner(companyId, auth);
        lock(companyId);
        requireAtLeast(companyId, actor, OWNER);
        var target = member(companyId, userId);
        if (OWNER.equals(target.getRole())) throw conflict("OWNER_ROLE_FIXED", "Use the ownership transfer to change the owner.");
        target.changeRole(role);
        var profile = users.summaries(List.of(userId)).get(userId);
        return new MemberDtos.Member(userId, profile.email(), profile.displayName(), role, target.getCreatedAt());
    }
    public void remove(UUID companyId, UUID userId, Authentication auth) {
        UUID actor = access.requireMember(companyId, auth);
        lock(companyId);
        String actorRole = requireAtLeast(companyId, actor, MEMBER);
        var target = member(companyId, userId);
        if (OWNER.equals(target.getRole())) {
            // Nobody else can remove the owner; the owner leaves only after transferring ownership.
            if (!userId.equals(actor)) throw ownerRequired();
            throw conflict("OWNER_MUST_TRANSFER", "The owner must transfer ownership before leaving.");
        }
        if (!userId.equals(actor)) {
            if (ADMIN.equals(target.getRole()) && !OWNER.equals(actorRole)) throw ownerRequired();
            if (rank(actorRole) < rank(ADMIN)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Company admin permission is required.");
            }
        }
        memberships.delete(target);
    }
    public void transferOwnership(UUID companyId, UUID userId, Authentication auth) {
        UUID actor = access.requireOwner(companyId, auth);
        lock(companyId);
        requireAtLeast(companyId, actor, OWNER);
        var target = member(companyId, userId);
        if (!ADMIN.equals(target.getRole())) {
            throw conflict("TRANSFER_TARGET_NOT_ADMIN", "Ownership can only be transferred to an admin.");
        }
        var owner = member(companyId, actor);
        owner.changeRole(ADMIN);
        // Demote first: the database allows exactly one OWNER row per company.
        memberships.flush();
        target.changeRole(OWNER);
    }

    private Company lock(UUID companyId) {
        return companies.findByIdForUpdate(companyId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found."));
    }
    /** Re-reads the actor's role after the company lock; READ_COMMITTED makes it the latest committed value. */
    private String requireAtLeast(UUID companyId, UUID actor, String minimum) {
        String role = memberships.currentRole(companyId, actor)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Company was not found."));
        if (rank(role) < rank(minimum)) {
            throw OWNER.equals(minimum) ? ownerRequired()
                : new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Company admin permission is required.");
        }
        return role;
    }
    private Membership member(UUID companyId, UUID userId) {
        return memberships.findByCompanyIdAndUserId(companyId, userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member was not found."));
    }
    /** Same rule as IdentityService.normalizeEmail. */
    private static String normalizeEmail(String value) { return value.strip().toLowerCase(Locale.ROOT); }
    private static MemberDtos.Invitation invitation(MemberInvitation i) {
        return new MemberDtos.Invitation(i.getId(), i.getEmail(), i.getRole(), i.getCreatedAt(), i.getExpiresAt());
    }
    private static ApiException invitationNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation was not found.");
    }
    private static ApiException ownerRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, "OWNER_REQUIRED", "Company owner permission is required.");
    }
    private static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }
}

package com.ticarvo.admin.service;

import com.ticarvo.admin.entity.AuditEntry;
import com.ticarvo.admin.entity.PlatformAdmin;
import com.ticarvo.admin.repository.AuditRepository;
import com.ticarvo.admin.repository.PlatformAdminRepository;
import com.ticarvo.identity.dto.AccountView;
import com.ticarvo.identity.service.AccountModeration;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/** Platform admin permission (ADR-008): read from the database on every request, so a revocation applies to open sessions at once. */
@Service
public class AdminAccess {
    private final PlatformAdminRepository admins;
    private final AuditRepository audit;
    private final CurrentUserProvider users;
    private final AccountModeration accounts;
    private final Clock clock;
    public AdminAccess(PlatformAdminRepository admins, AuditRepository audit, CurrentUserProvider users, AccountModeration accounts, Clock clock) {
        this.admins = admins; this.audit = audit; this.users = users; this.accounts = accounts; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public UUID requireAdmin(Authentication auth) {
        UUID id = users.currentUserId(auth);
        if (!admins.existsById(id)) throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Platform admin permission is required.");
        boolean mfaSession = auth.getAuthorities().stream().anyMatch(a -> AccountModeration.MFA_AUTHORITY.equals(a.getAuthority()));
        if (!mfaSession || !accounts.find(id).map(AccountView::mfaEnabled).orElse(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MFA_REQUIRED", "Log in with two-step login to use admin functions.");
        }
        return id;
    }
    public boolean isAdmin(UUID userId) { return admins.existsById(userId); }
    /** Call inside the transaction of the change it records. */
    public void record(UUID actor, String action, String targetType, UUID targetId) {
        audit.save(new AuditEntry(actor, action, targetType, targetId, clock.instant()));
    }

    /** Command line only; there is no HTTP path to these two. */
    @Transactional
    public String grant(String email) {
        var account = accounts.findByEmail(email).orElseThrow(() -> new IllegalStateException("No account for that email."));
        if (!account.emailVerified() || !account.mfaEnabled() || account.suspended()) {
            throw new IllegalStateException("The account needs a verified email, two-step login enabled and no suspension.");
        }
        if (admins.existsById(account.id())) return "Already a platform admin.";
        admins.save(new PlatformAdmin(account.id(), clock.instant()));
        record(null, "ADMIN_GRANTED", "USER", account.id());
        return "Granted.";
    }
    @Transactional
    public String revoke(String email) {
        var account = accounts.findByEmail(email).orElseThrow(() -> new IllegalStateException("No account for that email."));
        if (!admins.existsById(account.id())) return "Not a platform admin.";
        admins.deleteById(account.id());
        record(null, "ADMIN_REVOKED", "USER", account.id());
        return "Revoked.";
    }
}

package com.ticarvo.identity.service;

import com.ticarvo.identity.dto.AccountView;
import org.springframework.data.domain.Page;
import java.util.Optional;
import java.util.UUID;
/** Identity boundary for the admin module (ADR-008). */
public interface AccountModeration {
    /** Granted at login only after the TOTP code was checked. */
    String MFA_AUTHORITY = "MFA";
    Page<AccountView> search(String likePattern, int page, int size);
    Optional<AccountView> find(UUID userId);
    Optional<AccountView> findByEmail(String email);
    /** Must run inside the caller's transaction; 404 for an unknown user, 409 if already in that state. Suspending ends every session after commit. */
    AccountView suspend(UUID userId);
    AccountView restore(UUID userId);
}

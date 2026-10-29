package com.ticarvo.admin.service;

import com.ticarvo.identity.service.AccountDeletionGuard;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** ADR-013: a platform admin's rights are revoked from the command line before the account can be deleted. */
@Component
public class AdminAccountDeletion implements AccountDeletionGuard {
    private final AdminAccess admins;
    public AdminAccountDeletion(AdminAccess admins) { this.admins = admins; }
    @Override public void beforeDeletion(UUID userId) {
        if (admins.isAdmin(userId)) throw new ApiException(HttpStatus.CONFLICT, "ADMIN_MUST_BE_REVOKED", "Revoke platform admin rights first.");
    }
}

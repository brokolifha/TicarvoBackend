package com.ticarvo.identity.service;

import java.util.UUID;

/**
 * ADR-013 (KVKK art. 7): other modules decide, inside the deletion transaction, whether an account may go and what of
 * theirs it leaves behind. Throwing an ApiException stops the deletion.
 */
public interface AccountDeletionGuard {
    void beforeDeletion(UUID userId);
}

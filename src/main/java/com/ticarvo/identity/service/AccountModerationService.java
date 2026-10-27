package com.ticarvo.identity.service;

import com.ticarvo.identity.dto.AccountView;
import com.ticarvo.identity.entity.AppUser;
import com.ticarvo.identity.repository.UserRepository;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountModerationService implements AccountModeration {
    private final UserRepository users;
    private final AccountService accounts;
    private final Clock clock;
    public AccountModerationService(UserRepository users, AccountService accounts, Clock clock) {
        this.users = users; this.accounts = accounts; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Page<AccountView> search(String likePattern, int page, int size) {
        var request = PageRequest.of(page, size, Sort.by("email"));
        return (likePattern == null ? users.findAll(request) : users.search(likePattern, request)).map(AccountModerationService::view);
    }
    @Transactional(readOnly = true)
    public Optional<AccountView> find(UUID userId) { return users.findById(userId).map(AccountModerationService::view); }
    @Transactional(readOnly = true)
    public Optional<AccountView> findByEmail(String email) {
        return users.findByEmail(IdentityService.normalizeEmail(email)).map(AccountModerationService::view);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountView suspend(UUID userId) {
        var user = lock(userId);
        if (user.isSuspended()) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUSPENDED", "The account is already suspended.");
        user.suspend(clock.instant());
        String email = user.getEmail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { accounts.revokeAllSessions(email); }
        });
        return view(user);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountView restore(UUID userId) {
        var user = lock(userId);
        if (!user.isSuspended()) throw new ApiException(HttpStatus.CONFLICT, "NOT_SUSPENDED", "The account is not suspended.");
        user.restore();
        return view(user);
    }
    private AppUser lock(UUID userId) {
        return users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found."));
    }
    static AccountView view(AppUser user) {
        return new AccountView(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(),
            user.isEmailVerified(), user.isMfaEnabled(), user.isSuspended());
    }
}

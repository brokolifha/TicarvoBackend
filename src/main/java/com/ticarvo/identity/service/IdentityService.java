package com.ticarvo.identity.service;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.dto.UserResponse;
import com.ticarvo.identity.dto.UserSummary;
import com.ticarvo.identity.entity.AppUser;
import com.ticarvo.identity.repository.UserRepository;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class IdentityService implements CurrentUserProvider, UserDirectory {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AccountService accounts;
    public IdentityService(UserRepository users, PasswordEncoder passwords, AccountService accounts) {
        this.users = users; this.passwords = passwords; this.accounts = accounts;
    }
    public static String normalizeEmail(String value) { return value.strip().toLowerCase(Locale.ROOT); }
    static void requirePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Password must not exceed 72 UTF-8 bytes.");
        }
    }
    @Transactional
    public UserResponse register(RegisterRequest request) {
        requirePasswordBytes(request.password());
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_FAILED", "Registration could not be completed.");
        }
        var user = users.saveAndFlush(new AppUser(email, passwords.encode(request.password()), request.displayName().strip()));
        accounts.sendVerification(user);
        return response(user);
    }
    @Transactional(readOnly = true)
    public UUID currentUserId(Authentication authentication) { return current(authentication).getId(); }
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> summaries(Collection<UUID> userIds) {
        var result = new HashMap<UUID, UserSummary>();
        users.findAllById(userIds).forEach(user -> result.put(user.getId(), new UserSummary(user.getId(), user.getEmail(), user.getDisplayName())));
        return result;
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lock(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."));
    }
    @Transactional(readOnly = true)
    public UserResponse currentUser(Authentication authentication) { return response(current(authentication)); }
    private AppUser current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
        }
        return users.findByEmail(authentication.getName()).orElseThrow(() ->
            new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."));
    }
    private static UserResponse response(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.isMfaEnabled());
    }
}

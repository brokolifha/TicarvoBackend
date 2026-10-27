package com.ticarvo.identity.service;
import com.ticarvo.identity.entity.AccountToken;
import com.ticarvo.identity.entity.AccountToken.Purpose;
import com.ticarvo.identity.entity.AppUser;
import com.ticarvo.identity.repository.AccountTokenRepository;
import com.ticarvo.identity.repository.UserRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.mail.MailOutbox;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ticarvo.shared.security.BoundedRateLimiter;
import com.ticarvo.shared.security.SecureTokens;
import java.time.Clock;
import java.time.Duration;

/** Email verification, password reset and session revocation (ADR-004). */
@Service
public class AccountService {
    static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final MailOutbox mail;
    private final PasswordEncoder passwords;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Clock clock;
    public AccountService(UserRepository users, AccountTokenRepository tokens, MailOutbox mail, PasswordEncoder passwords,
            FindByIndexNameSessionRepository<? extends Session> sessions, Clock clock) {
        this.users = users; this.tokens = tokens; this.mail = mail; this.passwords = passwords;
        this.sessions = sessions; this.clock = clock;
        this.registrationNotices = new BoundedRateLimiter(clock, Duration.ofMinutes(15), 10000);
    }
    private final BoundedRateLimiter registrationNotices;

    @Transactional
    public void sendVerification(AppUser user) {
        issue(user, Purpose.EMAIL_VERIFICATION, VERIFICATION_TTL, "Ticarvo e-posta doğrulama",
            "E-posta adresinizi doğrulamak için bu kodu kullanın (24 saat geçerli):\n");
    }
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(IdentityService.normalizeEmail(email)).filter(user -> !user.isEmailVerified()).ifPresent(this::sendVerification);
    }
    /** Someone signed up with an address that already has an account (A-04): help the real owner, tell nobody else. */
    @Transactional
    public void registrationAttempt(AppUser user) {
        if (!user.isEmailVerified()) { sendVerification(user); return; }
        // At most one notice per account per window, so the sign-up form cannot flood a mailbox.
        if (!registrationNotices.allow(user.getId().toString(), 1)) return;
        mail.send(user.getEmail(), "Ticarvo kayıt denemesi",
            "Bu e-posta adresiyle yeni bir Ticarvo hesabı açılmak istendi. Hesabınız zaten var: giriş yapabilir ya da "
            + "parolanızı sıfırlayabilirsiniz. Bu isteği siz yapmadıysanız bu e-postayı yok sayabilirsiniz.");
    }
    @Transactional
    public void verifyEmail(String token) {
        var user = users.findById(consume(token, Purpose.EMAIL_VERIFICATION).getUserId()).orElseThrow(AccountService::invalidToken);
        user.markEmailVerified(clock.instant());
    }
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(IdentityService.normalizeEmail(email)).ifPresent(user -> issue(user, Purpose.PASSWORD_RESET, RESET_TTL,
            "Ticarvo parola sıfırlama", "Parolanızı sıfırlamak için bu kodu kullanın (30 dakika geçerli). İstemediyseniz yok sayın:\n"));
    }
    @Transactional
    public void resetPassword(String token, String newPassword) {
        IdentityService.requirePasswordBytes(newPassword);
        var user = users.findById(consume(token, Purpose.PASSWORD_RESET).getUserId()).orElseThrow(AccountService::invalidToken);
        user.changePassword(passwords.encode(newPassword));
        // Reaching the mailbox proves ownership of the address.
        user.markEmailVerified(clock.instant());
        tokens.deleteAll(user.getId(), Purpose.PASSWORD_RESET);
        String email = user.getEmail();
        // After commit, so a login racing the reset with the old password cannot outlive it.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { revokeAllSessions(email); }
        });
    }
    public void revokeAllSessions(String email) {
        sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
    }

    private void issue(AppUser user, Purpose purpose, Duration ttl, String subject, String text) {
        var now = clock.instant();
        // Silently skip: the caller always answers 202, so a recipient cannot be flooded.
        if (tokens.existsByUserIdAndPurposeAndCreatedAtAfter(user.getId(), purpose, now.minus(RESEND_COOLDOWN))) return;
        tokens.deleteAll(user.getId(), purpose);
        String token = SecureTokens.generate();
        tokens.saveAndFlush(new AccountToken(user.getId(), purpose, SecureTokens.sha256Hex(token), now, now.plus(ttl)));
        mail.send(user.getEmail(), subject, text + token);
    }
    private AccountToken consume(String token, Purpose purpose) {
        var stored = tokens.findForUpdate(SecureTokens.sha256Hex(token), purpose)
            .filter(candidate -> candidate.isValidAt(clock.instant()))
            .orElseThrow(AccountService::invalidToken);
        tokens.delete(stored);
        return stored;
    }
    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "TOKEN_INVALID", "The code is invalid or has expired.");
    }
}

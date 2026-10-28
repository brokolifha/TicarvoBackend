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
        this.passwordChecks = new BoundedRateLimiter(clock, Duration.ofMinutes(15), 10000);
    }
    private final BoundedRateLimiter registrationNotices;
    /** Current-password checks in account settings: 5 per user per 15 minutes. */
    private final BoundedRateLimiter passwordChecks;
    static final Duration EMAIL_CHANGE_TTL = Duration.ofHours(1);

    @Transactional
    public void sendVerification(AppUser user) {
        issue(user, Purpose.EMAIL_VERIFICATION, VERIFICATION_TTL, "Ticarvo e-posta doğrulama",
            "E-posta adresinizi doğrulamak için " + mail.page("/e-posta-dogrula") + " sayfasında bu kodu girin (24 saat geçerli):\n");
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
            "Ticarvo parola sıfırlama", "Parolanızı sıfırlamak için " + mail.page("/parola-sifirla") + " sayfasında bu kodu girin (30 dakika geçerli). İstemediyseniz yok sayın:\n"));
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
    // ---- C-03 account settings ----
    /** Other devices are logged out; the session that made the change stays. */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword, String keepSessionId) {
        var user = withCurrentPassword(email, currentPassword);
        IdentityService.requirePasswordBytes(newPassword);
        user.changePassword(passwords.encode(newPassword));
        tokens.deleteAll(user.getId(), Purpose.PASSWORD_RESET);
        afterCommit(() -> sessions.findByPrincipalName(email).keySet().stream().filter(id -> !id.equals(keepSessionId)).forEach(sessions::deleteById));
        mail.send(email, "Ticarvo parolanız değişti", "Hesabınızın parolası değiştirildi. Bunu siz yapmadıysanız hemen parolanızı sıfırlayın.");
    }
    /** Always 202: an address already in use is told to its owner, not to the requester. */
    @Transactional
    public void requestEmailChange(String email, String newEmail, String currentPassword) {
        var user = withCurrentPassword(email, currentPassword);
        String target = IdentityService.normalizeEmail(newEmail);
        if (target.equals(user.getEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The new address is the current one.");
        }
        if (users.existsByEmail(target)) {
            mail.send(target, "Ticarvo e-posta değişikliği", "Bir Ticarvo hesabı e-posta adresini bu adrese taşımak istedi; adres zaten kullanıldığı için işlem yapılmadı.");
            return;
        }
        var now = clock.instant();
        tokens.deleteAll(user.getId(), Purpose.EMAIL_CHANGE);
        String token = SecureTokens.generate();
        tokens.saveAndFlush(AccountToken.emailChange(user.getId(), target, SecureTokens.sha256Hex(token), now, now.plus(EMAIL_CHANGE_TTL)));
        mail.send(email, "Ticarvo e-posta değişikliği isteği", "Hesabınızın e-posta adresini değiştirme isteği alındı. Bunu siz yapmadıysanız parolanızı değiştirin.");
        mail.send(target, "Ticarvo yeni e-posta adresinizi doğrulayın", "Yeni adresinizi onaylamak için " + mail.page("/panel/hesap") + " sayfasında bu kodu girin (1 saat geçerli):\n" + token);
    }
    /** Needs the signed-in account that asked; afterwards every session ends and the new address is the login. */
    @Transactional
    public void confirmEmailChange(String email, String token) {
        var user = users.lockByEmail(email).orElseThrow(AccountService::invalidToken);
        var stored = consume(token, Purpose.EMAIL_CHANGE);
        if (!stored.getUserId().equals(user.getId())) throw invalidToken();
        if (users.existsByEmail(stored.getNewEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "This address is already in use.");
        }
        user.changeEmail(stored.getNewEmail(), clock.instant());
        // Sessions are indexed by the old address, which no longer logs in.
        afterCommit(() -> revokeAllSessions(email));
        mail.send(email, "Ticarvo e-posta adresiniz değişti", "Hesabınızın e-posta adresi değiştirildi. Bunu siz yapmadıysanız destekle iletişime geçin.");
    }
    private AppUser withCurrentPassword(String email, String currentPassword) {
        var user = users.lockByEmail(email).orElseThrow(() ->
            new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."));
        if (!passwordChecks.allow(user.getId().toString(), 5)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later.");
        }
        if (!passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INVALID", "The current password is not correct.");
        }
        return user;
    }
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
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

package com.ticarvo.identity.service;
import com.ticarvo.identity.dto.MfaResponses;
import com.ticarvo.identity.entity.AccountToken;
import com.ticarvo.identity.entity.AccountToken.Purpose;
import com.ticarvo.identity.entity.AppUser;
import com.ticarvo.identity.repository.AccountTokenRepository;
import com.ticarvo.identity.repository.UserRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.BoundedRateLimiter;
import com.ticarvo.shared.security.SecureTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** TOTP two-step login (ADR-005). */
@Service
public class MfaService {
    static final int MAX_ATTEMPTS = 5;
    static final int RECOVERY_CODE_COUNT = 10;
    static final Duration RECOVERY_TTL = Duration.ofDays(3650);
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final Clock clock;
    private final SecretKeySpec key;
    /** REL-M4-MFAKEY: earlier keys still decrypt, so a new key can be rolled out without locking anyone out. */
    private final List<SecretKeySpec> previousKeys;
    private final BoundedRateLimiter attempts;
    private final SecureRandom random = new SecureRandom();
    /** Login form `code`, carried as authentication details; transient so it never reaches the stored session. */
    public static final class LoginCode implements java.io.Serializable {
        private final transient String value;
        public LoginCode(String value) { this.value = value; }
        public String value() { return value; }
        @Override public String toString() { return "LoginCode[REDACTED]"; }
    }
    public MfaService(UserRepository users, AccountTokenRepository tokens, Clock clock,
            @Value("${ticarvo.mfa.encryption-key:}") String encodedKey, @Value("${ticarvo.mfa.previous-keys:}") String previous) {
        this.users = users; this.tokens = tokens; this.clock = clock;
        this.key = aesKey(encodedKey, "ticarvo.mfa.encryption-key");
        this.previousKeys = Arrays.stream(previous.split(",")).map(String::strip).filter(k -> !k.isEmpty())
            .map(k -> aesKey(k, "ticarvo.mfa.previous-keys")).toList();
        this.attempts = new BoundedRateLimiter(clock, Duration.ofMinutes(15), 10000);
    }

    @Transactional
    public MfaResponses.Setup setup(String email) {
        var user = account(email);
        if (user.isMfaEnabled()) throw conflict("MFA_ALREADY_ENABLED", "Two-step login is already enabled.");
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        user.startMfaSetup(encrypt(secret, user.getId()));
        String encoded = base32(secret);
        String label = URLEncoder.encode("Ticarvo:" + user.getEmail(), StandardCharsets.UTF_8).replace("+", "%20");
        return new MfaResponses.Setup(encoded,
            "otpauth://totp/" + label + "?secret=" + encoded + "&issuer=Ticarvo&algorithm=SHA1&digits=6&period=30");
    }
    @Transactional
    public MfaResponses.RecoveryCodes enable(String email, String code) {
        var user = account(email);
        if (user.isMfaEnabled()) throw conflict("MFA_ALREADY_ENABLED", "Two-step login is already enabled.");
        if (user.getMfaSecret() == null) throw conflict("MFA_SETUP_REQUIRED", "Start the setup first.");
        limitAttempts(user);
        if (!acceptTotp(user, code)) throw invalidCode();
        user.enableMfa();
        tokens.deleteAll(user.getId(), Purpose.MFA_RECOVERY);
        var now = clock.instant();
        var codes = new ArrayList<String>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] bytes = new byte[10];
            random.nextBytes(bytes);
            String plain = base32(bytes);
            tokens.save(new AccountToken(user.getId(), Purpose.MFA_RECOVERY, SecureTokens.sha256Hex(plain), now, now.plus(RECOVERY_TTL)));
            codes.add(plain.substring(0, 4) + "-" + plain.substring(4, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12));
        }
        return new MfaResponses.RecoveryCodes(codes);
    }
    @Transactional
    public void disable(String email, String code) {
        var user = account(email);
        if (!user.isMfaEnabled()) throw conflict("MFA_NOT_ENABLED", "Two-step login is not enabled.");
        limitAttempts(user);
        if (!acceptTotp(user, code) && !consumeRecovery(user, code)) throw invalidCode();
        user.disableMfa();
        tokens.deleteAll(user.getId(), Purpose.MFA_RECOVERY);
    }
    /** Second factor during login; the caller has already verified the password. */
    @Transactional
    public void verifyLogin(String email, String code) {
        var user = users.lockByEmail(email).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!user.isMfaEnabled()) return;
        if (code == null || code.isBlank()) throw mfaRequired();
        if (!attempts.allow(user.getId().toString(), MAX_ATTEMPTS)) {
            throw new MfaAuthenticationException("MFA_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many code attempts. Try again later.");
        }
        if (!acceptTotp(user, code) && !consumeRecovery(user, code)) throw mfaRequired();
    }

    private AppUser account(String email) {
        return users.lockByEmail(email).orElseThrow(() ->
            new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."));
    }
    private void limitAttempts(AppUser user) {
        if (!attempts.allow(user.getId().toString(), MAX_ATTEMPTS)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later.");
        }
    }
    private boolean acceptTotp(AppUser user, String code) {
        if (code == null || !code.matches("[0-9]{6}")) return false;
        var opened = decrypt(user.getMfaSecret(), user.getId());
        byte[] secret = opened.secret();
        // Lazy rotation: a secret still sealed with an earlier key is resealed with the current one at the next use.
        if (!opened.currentKey()) user.resealMfaSecret(encrypt(secret, user.getId()));
        long current = clock.millis() / 30_000;
        for (long step = current - 1; step <= current + 1; step++) {
            if (MessageDigest.isEqual(totp(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return user.acceptMfaStep(step);
            }
        }
        return false;
    }
    private boolean consumeRecovery(AppUser user, String code) {
        if (code == null) return false;
        String normalized = code.replace("-", "").strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-7]{16}")) return false;
        var stored = tokens.findForUpdate(SecureTokens.sha256Hex(normalized), Purpose.MFA_RECOVERY)
            .filter(token -> token.getUserId().equals(user.getId()));
        stored.ifPresent(tokens::delete);
        return stored.isPresent();
    }

    /** RFC 6238 TOTP with HMAC-SHA1 and 6 digits. */
    static String totp(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    /** RFC 4648 base32 without padding, as authenticator apps expect. */
    static String base32(byte[] data) {
        var out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
    static SecretKeySpec aesKey(String encoded, String name) {
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException invalid) { raw = new byte[0]; }
        if (raw.length != 32) throw new IllegalStateException(name + " must be base64 encoded 32-byte keys.");
        return new SecretKeySpec(raw, "AES");
    }
    record Opened(byte[] secret, boolean currentKey) {}
    /** --ticarvo.mfa.reseal: reseals every stored secret with the current key, so an earlier key can be retired. */
    @Transactional
    public int resealAll() {
        int changed = 0;
        for (var user : users.findByMfaSecretIsNotNull()) {
            var opened = decrypt(user.getMfaSecret(), user.getId());
            if (!opened.currentKey()) { user.resealMfaSecret(encrypt(opened.secret(), user.getId())); changed++; }
        }
        return changed;
    }
    String encrypt(byte[] secret, UUID user) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, iv, user, key).doFinal(secret);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
    /** Tries the current key, then the earlier ones; GCM authentication tells a wrong key apart. */
    Opened decrypt(String stored, UUID user) {
        byte[] all = Base64.getDecoder().decode(stored);
        var keys = new java.util.ArrayList<SecretKeySpec>(previousKeys.size() + 1);
        keys.add(key); keys.addAll(previousKeys);
        for (int i = 0; i < keys.size(); i++) {
            try {
                return new Opened(cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(all, 0, 12), user, keys.get(i)).doFinal(all, 12, all.length - 12), i == 0);
            } catch (javax.crypto.AEADBadTagException wrongKey) {
                // Next key.
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException(failure);
            }
        }
        throw new IllegalStateException("No configured MFA key opens this secret.");
    }
    // The user id is authenticated data, so a ciphertext copied to another account fails to decrypt.
    private static Cipher cipher(int mode, byte[] iv, UUID user, SecretKeySpec key) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(user.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }
    private static MfaAuthenticationException mfaRequired() {
        return new MfaAuthenticationException("MFA_REQUIRED", HttpStatus.UNAUTHORIZED, "A valid authentication code is required.");
    }
    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MFA_CODE_INVALID", "The authentication code is invalid.");
    }
    private static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }
}

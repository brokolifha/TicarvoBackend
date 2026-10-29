package com.ticarvo.identity.service;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MfaAlgorithmTest {
    /** RFC 6238 Appendix B (SHA-1 seed), truncated to the 6 digits authenticator apps show. */
    @Test void totpMatchesRfc6238Vectors() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("287082", MfaService.totp(seed, 59L / 30));
        assertEquals("081804", MfaService.totp(seed, 1111111109L / 30));
        assertEquals("050471", MfaService.totp(seed, 1111111111L / 30));
        assertEquals("005924", MfaService.totp(seed, 1234567890L / 30));
        assertEquals("279037", MfaService.totp(seed, 2000000000L / 30));
    }
    /** REL-M4-MFAKEY: a secret sealed with an earlier key still opens and is reported for resealing; a removed key does not open it. */
    @Test void earlierKeysStillOpenSecretsUntilRetired() {
        String k1 = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        byte[] raw = new byte[32]; raw[0] = 1; String k2 = java.util.Base64.getEncoder().encodeToString(raw);
        var user = java.util.UUID.randomUUID(); byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String sealed = new MfaService(null, null, java.time.Clock.systemUTC(), k1, "").encrypt(secret, user);
        var rotated = new MfaService(null, null, java.time.Clock.systemUTC(), k2, " " + k1 + " ");
        var opened = rotated.decrypt(sealed, user);
        assertArrayEquals(secret, opened.secret()); assertFalse(opened.currentKey());
        assertTrue(rotated.decrypt(rotated.encrypt(secret, user), user).currentKey());
        assertEquals(48, java.util.Base64.getDecoder().decode(sealed).length, "stored format unchanged");
        assertThrows(IllegalStateException.class, () -> new MfaService(null, null, java.time.Clock.systemUTC(), k2, "").decrypt(sealed, user));
        assertThrows(IllegalStateException.class, () -> new MfaService(null, null, java.time.Clock.systemUTC(), k2, "not-a-key"));
    }
    /** RFC 4648 section 10 vectors without padding. */
    @Test void base32MatchesRfc4648() {
        assertEquals("MZXW6YTBOI", MfaService.base32("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6", MfaService.base32("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MY", MfaService.base32("f".getBytes(StandardCharsets.US_ASCII)));
    }
}

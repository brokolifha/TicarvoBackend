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
    /** RFC 4648 section 10 vectors without padding. */
    @Test void base32MatchesRfc4648() {
        assertEquals("MZXW6YTBOI", MfaService.base32("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6", MfaService.base32("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MY", MfaService.base32("f".getBytes(StandardCharsets.US_ASCII)));
    }
}

package com.ticarvo.shared.security;
import com.ticarvo.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class BoundedRateLimiterTest {
    @Test void capacityIsBoundedAndWindowExpires() {
        var clock=new MutableClock(); var limiter=new BoundedRateLimiter(clock,Duration.ofSeconds(5),2);
        assertTrue(limiter.allow("a",2)); assertTrue(limiter.allow("a",2));
        assertFalse(limiter.allow("a",2)); assertTrue(limiter.allow("b",2)); assertFalse(limiter.allow("c",2));
        clock.now=clock.now.plusSeconds(5); assertTrue(limiter.allow("c",2)); assertTrue(limiter.allow("a",2));
    }
    @Test void principalQuotaDoesNotIncludeCompanyAndOperationsAreIndependent() {
        var clock=new MutableClock(); var limiter=new PrincipalRateLimit(clock,900,10000,60,10,30,10,30,120,300,10,30);
        UUID user=UUID.randomUUID(),other=UUID.randomUUID();
        for(int i=0;i<30;i++) limiter.check(PrincipalRateLimit.Operation.APPLY,user);
        assertThrows(ApiException.class,()->limiter.check(PrincipalRateLimit.Operation.APPLY,user));
        assertDoesNotThrow(()->limiter.check(PrincipalRateLimit.Operation.APPLY,other));
        assertDoesNotThrow(()->limiter.check(PrincipalRateLimit.Operation.ROTATE,user));
        clock.now=clock.now.plusSeconds(900); assertDoesNotThrow(()->limiter.check(PrincipalRateLimit.Operation.APPLY,user));
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-02T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
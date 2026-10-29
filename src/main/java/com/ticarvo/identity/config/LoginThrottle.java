package com.ticarvo.identity.config;

import com.ticarvo.identity.service.IdentityService;
import com.ticarvo.shared.security.BoundedRateLimiter;
import com.ticarvo.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * A-05: wrong passwords are also counted per submitted address, so guesses spread over many IPs still stop. The count
 * is kept for any address, registered or not, so the answer never tells whether an account exists. A failure lockout
 * can be triggered by someone else; it is soft (15 minutes) and the owner can still reset the password.
 */
public final class LoginThrottle extends OncePerRequestFilter {
    static final int FAILURES = 10;
    private final BoundedRateLimiter failures;
    private final ProblemWriter problems;
    LoginThrottle(Clock clock, ProblemWriter problems) {
        this.failures = new BoundedRateLimiter(clock, Duration.ofMinutes(15), 10000);
        this.problems = problems;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(request.getServletPath()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (failures.exhausted(key(request), FAILURES)) {
            response.setHeader("Retry-After", "900");
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later.");
            return;
        }
        chain.doFilter(request, response);
    }
    /** Called by the login failure handler for a wrong address or password. */
    void failed(HttpServletRequest request) { failures.allow(key(request), Integer.MAX_VALUE); }
    private static String key(HttpServletRequest request) {
        String email = request.getParameter("email");
        return "LOGIN_FAIL:" + (email == null ? "" : IdentityService.normalizeEmail(email));
    }
}

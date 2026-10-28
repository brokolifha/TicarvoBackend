package com.ticarvo.shared.security;
import com.ticarvo.shared.web.ProblemWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.time.*;

public final class RequestProtectionFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_IMAGE_UPLOAD_BYTES = 6 * 1024 * 1024;
    private static final int LIMITER_CAPACITY = 10000;
    // Per client address per 15 minutes; mail-sending endpoints are the tightest.
    private static final java.util.Map<String, Integer> ANONYMOUS_POST_LIMITS = java.util.Map.of(
        "/api/v1/auth/login", 20, "/api/v1/auth/register", 10,
        "/api/v1/auth/email-verification/resend", 5, "/api/v1/auth/password-reset/request", 5,
        "/api/v1/auth/email-verification", 20, "/api/v1/auth/password-reset", 20, "/api/v1/moderation/appeals", 10);
    private final ProblemWriter problems;
    private final BoundedRateLimiter authLimiter;
    // Separate key space: anonymous catalog reads must not exhaust the login/register capacity (SEC-M3-001).
    private final BoundedRateLimiter publicCatalogLimiter;
    public RequestProtectionFilter(ProblemWriter problems) {
        this(problems,
            new BoundedRateLimiter(Clock.systemUTC(), Duration.ofMinutes(15), LIMITER_CAPACITY),
            new BoundedRateLimiter(Clock.systemUTC(), Duration.ofMinutes(15), LIMITER_CAPACITY));
    }
    RequestProtectionFilter(ProblemWriter problems, BoundedRateLimiter authLimiter, BoundedRateLimiter publicCatalogLimiter) {
        this.problems = problems;
        this.authLimiter = authLimiter;
        this.publicCatalogLimiter = publicCatalogLimiter;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String servletPath = request.getServletPath();
        if ("GET".equals(request.getMethod()) && servletPath.startsWith("/api/v1/catalog/")) {
            // Images get their own, larger budget: one listing page loads up to 20 cover images (ADR-007).
            boolean image = servletPath.matches("/api/v1/catalog/products/[^/]+/images/[^/]+");
            boolean listing = servletPath.equals("/api/v1/catalog/products") || servletPath.equals("/api/v1/catalog/categories")
                || servletPath.matches("/api/v1/catalog/products/[^/]+") || servletPath.matches("/api/v1/catalog/sellers/[^/]+/terms");
            if ((image && !publicCatalogLimiter.allow("PUBLIC_IMAGE:" + request.getRemoteAddr(), 600))
                    || (listing && !publicCatalogLimiter.allow("PUBLIC_CATALOG:" + request.getRemoteAddr(), 120))) {
                response.setHeader("Retry-After", "900");
                problems.write(response,HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Please try again later."); return;
            }
        }
        if (java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod())) { chain.doFilter(request, response); return; }
        // Only the product image upload may exceed 16 KiB; Spring multipart then caps the file at 5 MB (ADR-007).
        long maxBody = "POST".equals(request.getMethod()) && servletPath.matches("/api/v1/companies/[^/]+/products/[^/]+/images")
            ? MAX_IMAGE_UPLOAD_BYTES : MAX_BODY_BYTES;
        if (request.getContentLengthLong() > maxBody) {
            problems.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body is too large."); return;
        }
        String path = request.getServletPath();
        Integer limit = "POST".equals(request.getMethod()) ? ANONYMOUS_POST_LIMITS.get(path) : null;
        if (limit != null) {
            // getRemoteAddr is the caller, or the X-Forwarded-For client only when Tomcat trusts the proxy (SEC-M1-002).
            if (!authLimiter.allow(path + ":" + request.getRemoteAddr(), limit)) {
                response.setHeader("Retry-After", "900");
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later."); return;
            }
        }
        String type = request.getContentType();
        String mediaType = type == null ? "" : type.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        boolean jsonBody = mediaType.equals("application/json")
            || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
        if (jsonBody) {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                problems.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body exceeds 16 KiB."); return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } else {
            chain.doFilter(request, response);
        }
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API"); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}

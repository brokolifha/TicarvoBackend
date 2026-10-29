package com.ticarvo.identity.config;
import com.ticarvo.identity.service.MfaAuthenticationException;
import com.ticarvo.identity.service.MfaService;
import com.ticarvo.shared.security.RequestProtectionFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import com.ticarvo.shared.web.ProblemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        var bcrypt = new BCryptPasswordEncoder(12);
        return new PasswordEncoder() {
            @Override public String encode(CharSequence raw) { return bcrypt.encode(raw); }
            @Override public boolean matches(CharSequence raw, String encoded) {
                if (raw == null || raw.toString().getBytes(StandardCharsets.UTF_8).length > 72) return false;
                return bcrypt.matches(raw, encoded);
            }
        };
    }
    /** Password, then the TOTP/recovery code (ADR-005), then Spring's post checks (email verified). */
    @Bean DaoAuthenticationProvider authenticationProvider(UserDetailsService users, PasswordEncoder passwords, MfaService mfa) {
        var provider = new DaoAuthenticationProvider(users) {
            @Override protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken authentication) {
                super.additionalAuthenticationChecks(user, authentication);
                mfa.verifyLogin(user.getUsername(), authentication.getDetails() instanceof MfaService.LoginCode code ? code.value() : null);
            }
        };
        provider.setPasswordEncoder(passwords);
        // Account status only after the password and code: a suspension must not leak to someone without them (ADR-008).
        provider.setPreAuthenticationChecks(user -> {});
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return provider;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemWriter problems,
        @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        http.cors(config -> config.configurationSource(cors))
            .csrf(Customizer.withDefaults())
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/auth/csrf", "/api/v1/sellers", "/api/v1/sellers/{id}", "/api/v1/sellers/{id}/logo", "/api/v1/catalog/products", "/api/v1/catalog/products/{productId}",
                    "/api/v1/catalog/products/{productId}/images/{imageId}", "/api/v1/catalog/categories", "/api/v1/catalog/sellers/{sellerId}/terms").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/email-verification",
                    "/api/v1/auth/email-verification/resend", "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset", "/api/v1/moderation/appeals",
                    "/api/v1/notices", "/api/v1/notices/confirm").permitAll()
                .requestMatchers("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/email-change", "/api/v1/auth/email-change/confirm", "/api/v1/auth/sessions/revoke-all", "/api/v1/auth/mfa/*", "/api/v1/member-invitations/accept", "/api/v1/companies", "/api/v1/companies/**", "/api/v1/trade-invitations/resolve",
                    "/api/v1/complaints", "/api/v1/complaints/*", "/api/v1/admin/**", "/api/v1/me/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> problems.write(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."))
                .accessDeniedHandler((request, response, exception) -> problems.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Request is not permitted.")))
            .formLogin(login -> login.loginProcessingUrl("/api/v1/auth/login").usernameParameter("email")
                .authenticationDetailsSource(request -> new MfaService.LoginCode(request.getParameter("code")))
                .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                .failureHandler((request, response, exception) -> {
                    // All of these are raised only after the password matched.
                    if (exception instanceof MfaAuthenticationException mfa) {
                        if (mfa.status() == HttpStatus.TOO_MANY_REQUESTS) response.setHeader("Retry-After", "900");
                        problems.write(response, mfa.status(), mfa.code(), mfa.getMessage());
                    } else if (exception instanceof DisabledException) {
                        problems.write(response, HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "The account is suspended.");
                    } else if (exception instanceof CredentialsExpiredException) {
                        problems.write(response, HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Email address is not verified.");
                    } else {
                        problems.write(response, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
                    }
                }))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
            .addFilterBefore(new RequestProtectionFilter(problems), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${ticarvo.cors.allowed-origins:}") String origins) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!origins.isBlank()) {
            var config = new CorsConfiguration();
            var allowed = Arrays.stream(origins.split(",")).map(String::strip).toList();
            if (allowed.stream().anyMatch(origin -> origin.contains("*") || !(origin.startsWith("https://") || origin.startsWith("http://localhost:")))) {
                throw new IllegalArgumentException("CORS requires explicit HTTPS origins or localhost development origins.");
            }
            config.setAllowedOrigins(allowed);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
            config.setAllowCredentials(true);
            config.setMaxAge(600L);
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }
}

package com.ticarvo.identity.controller;
import com.ticarvo.identity.dto.AccountRequests;
import com.ticarvo.identity.dto.CsrfResponse;
import com.ticarvo.identity.dto.MfaResponses;
import com.ticarvo.identity.service.AccountService;
import com.ticarvo.identity.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import com.ticarvo.identity.dto.RegisterRequest;
import com.ticarvo.identity.dto.UserResponse;
import com.ticarvo.identity.service.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identity;
    private final AccountService accounts;
    private final MfaService mfa;
    public AuthController(IdentityService identity, AccountService accounts, MfaService mfa) {
        this.identity = identity; this.accounts = accounts; this.mfa = mfa;
    }
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(), token.getToken()); }
    /** 202 with no body whether or not the address is new (A-04); the next step is always "check your email". */
    @PostMapping("/register") @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest request) { identity.requestRegistration(request); }
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) { return identity.currentUser(authentication); }
    @PostMapping("/email-verification") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody AccountRequests.Token request) { accounts.verifyEmail(request.token()); }
    @PostMapping("/email-verification/resend") @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody AccountRequests.EmailAddress request) { accounts.resendVerification(request.email()); }
    @PostMapping("/password-reset/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody AccountRequests.EmailAddress request) { accounts.requestPasswordReset(request.email()); }
    @PostMapping("/password-reset") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody AccountRequests.PasswordReset request) {
        accounts.resetPassword(request.token(), request.newPassword());
    }
    @PostMapping("/mfa/setup")
    public MfaResponses.Setup setupMfa(Authentication authentication) { return mfa.setup(authentication.getName()); }
    @PostMapping("/mfa/enable")
    public MfaResponses.RecoveryCodes enableMfa(@Valid @RequestBody AccountRequests.MfaCode request, Authentication authentication) {
        return mfa.enable(authentication.getName(), request.code());
    }
    @PostMapping("/mfa/disable") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa(@Valid @RequestBody AccountRequests.MfaCode request, Authentication authentication) {
        mfa.disable(authentication.getName(), request.code());
    }
    @PostMapping("/sessions/revoke-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllSessions(Authentication authentication, HttpServletRequest request) {
        accounts.revokeAllSessions(authentication.getName());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
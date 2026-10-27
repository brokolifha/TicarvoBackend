package com.ticarvo.identity.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;

/** Second-factor failure during login; raised only after the password matched. */
public class MfaAuthenticationException extends AuthenticationException {
    private final String code;
    private final HttpStatus status;
    public MfaAuthenticationException(String code, HttpStatus status, String detail) {
        super(detail);
        this.code = code; this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
}

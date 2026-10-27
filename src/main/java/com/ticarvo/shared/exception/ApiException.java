package com.ticarvo.shared.exception;

import org.springframework.http.HttpStatus;
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final int retryAfterSeconds;
    public ApiException(HttpStatus status,String code,String detail) { this(status,code,detail,900); }
    public ApiException(HttpStatus status,String code,String detail,int retryAfterSeconds) {
        super(detail); this.status=status; this.code=code; this.retryAfterSeconds=retryAfterSeconds;
    }
    public HttpStatus status(){return status;} public String code(){return code;}
    public int retryAfterSeconds(){return retryAfterSeconds;}
}

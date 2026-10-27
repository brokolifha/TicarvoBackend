package com.ticarvo.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException exception) {
        var response=ResponseEntity.status(exception.status());
        if(exception.status()==HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After",Integer.toString(exception.retryAfterSeconds()));
        return response.body(problem(exception.status(),exception.code(),exception.getMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> conflict(DataIntegrityViolationException exception,HttpServletRequest request) {
        // Two sign-ups racing for one address: the loser answers like every other sign-up (A-04).
        if(request.getRequestURI().equals("/api/v1/auth/register")) return ResponseEntity.accepted().build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT,"DATA_CONFLICT","Request conflicts with existing data."));
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> versionConflict(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT,versionCode(exception),"Resource changed. Reload before retrying."));
    }
    /** From the entity that failed, not the URL (ARCH-M3-03): /orders/... must never answer with a product code. */
    static String versionCode(OptimisticLockingFailureException exception) {
        String entity=exception instanceof org.springframework.orm.ObjectOptimisticLockingFailureException e && e.getPersistentClassName()!=null
            ? e.getPersistentClassName().substring(e.getPersistentClassName().lastIndexOf('.')+1) : "";
        return switch(entity) {
            case "Product" -> "PRODUCT_VERSION_CONFLICT";
            case "TradeRelationship" -> "RELATIONSHIP_VERSION_CONFLICT";
            case "PurchaseOrder" -> "ORDER_VERSION_CONFLICT";
            default -> "VERSION_CONFLICT";
        };
    }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Object> lockConflict(PessimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT,"CONCURRENT_OPERATION","A concurrent operation prevented completion. Reload before retrying."));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","An unexpected error occurred."));
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception,Object body,HttpHeaders headers,HttpStatusCode status,WebRequest request) {
        var problem=problem(status,"REQUEST_INVALID","Request could not be processed.");
        var errors=fieldErrors(exception);
        if(!errors.isEmpty()) problem.setProperty("errors",errors);
        return new ResponseEntity<>(problem,headers,status);
    }
    /** Which field broke which rule (A-06), so forms can mark it. Never the submitted value. */
    static java.util.List<java.util.Map<String,String>> fieldErrors(Exception exception) {
        if(exception instanceof org.springframework.web.bind.MethodArgumentNotValidException invalid) {
            return invalid.getBindingResult().getFieldErrors().stream()
                .map(error->java.util.Map.of("field",error.getField(),"code",String.valueOf(error.getCode()))).distinct().toList();
        }
        if(exception instanceof org.springframework.http.converter.HttpMessageNotReadableException unreadable
                && unreadable.getCause() instanceof tools.jackson.core.JacksonException json && !json.getPath().isEmpty()) {
            var path=new StringBuilder();
            for(var reference:json.getPath()) {
                if(reference.getPropertyName()!=null) path.append(path.isEmpty()?"":".").append(reference.getPropertyName());
                else if(reference.getIndex()>=0) path.append('[').append(reference.getIndex()).append(']');
            }
            String code=json instanceof tools.jackson.databind.exc.UnrecognizedPropertyException ? "UNKNOWN_FIELD" : "INVALID_FORMAT";
            return java.util.List.of(java.util.Map.of("field",path.toString(),"code",code));
        }
        return java.util.List.of();
    }
    static ProblemDetail problem(HttpStatusCode status,String code,String detail) {
        var problem=ProblemDetail.forStatusAndDetail(status,detail); problem.setProperty("code",code); return problem;
    }
}
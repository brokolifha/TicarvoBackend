package com.ticarvo.shared.security;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class PrincipalRateLimit {
    public enum Operation { RESOLVE, ROTATE, APPLY, COMPANY_CREATE, PRODUCT_CREATE, PRODUCT_MUTATE, CATALOG_READ, COMPLAINT_CREATE, ORDER_CREATE }
    private final BoundedRateLimiter limiter;
    private final int windowSeconds;
    private final Map<Operation, Integer> limits;
    public PrincipalRateLimit(Clock clock,
            @Value("${ticarvo.limits.window-seconds:900}") int windowSeconds,
            @Value("${ticarvo.limits.capacity:10000}") int capacity,
            @Value("${ticarvo.limits.resolve:60}") int resolve,
            @Value("${ticarvo.limits.rotate:10}") int rotate,
            @Value("${ticarvo.limits.apply:30}") int apply,
            @Value("${ticarvo.limits.company-create:10}") int companyCreate,
            @Value("${ticarvo.limits.product-create:30}") int productCreate,
            @Value("${ticarvo.limits.product-mutate:120}") int productMutate,
            @Value("${ticarvo.limits.catalog-read:300}") int catalogRead,
            @Value("${ticarvo.limits.complaint-create:10}") int complaintCreate,
            @Value("${ticarvo.limits.order-create:30}") int orderCreate) {
        if (windowSeconds < 1 || capacity < 1 || resolve < 1 || rotate < 1 || apply < 1 || companyCreate < 1 || productCreate < 1 || productMutate < 1 || catalogRead < 1 || complaintCreate < 1 || orderCreate < 1) {
            throw new IllegalArgumentException("Rate limits must be positive.");
        }
        this.windowSeconds = windowSeconds;
        limiter = new BoundedRateLimiter(clock, Duration.ofSeconds(windowSeconds), capacity);
        limits = Map.of(Operation.RESOLVE, resolve, Operation.ROTATE, rotate,
            Operation.APPLY, apply, Operation.COMPANY_CREATE, companyCreate, Operation.PRODUCT_CREATE, productCreate, Operation.PRODUCT_MUTATE, productMutate, Operation.CATALOG_READ, catalogRead, Operation.COMPLAINT_CREATE, complaintCreate, Operation.ORDER_CREATE, orderCreate);
    }
    public void check(Operation operation, UUID userId) {
        if (!limiter.allow(operation.name() + ":" + userId, limits.get(operation))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please try again later.", windowSeconds);
        }
    }
}

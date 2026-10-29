package com.ticarvo.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Fixed-window, process-local protection. Full capacity fails closed; active entries are not evicted. */
public final class BoundedRateLimiter {
    private final Clock clock;
    private final long windowMillis;
    private final int capacity;
    private final Map<String, Window> windows = new HashMap<>();
    public BoundedRateLimiter(Clock clock, Duration window, int capacity) {
        this.clock = clock; this.windowMillis = window.toMillis(); this.capacity = capacity;
    }
    public synchronized boolean allow(String key, int limit) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= capacity) return false;
            window = new Window(now + windowMillis);
            windows.put(key, window);
        }
        if (window.count >= limit) return false;
        window.count++;
        return true;
    }
    /** Whether the key already used its limit in the current window; counts nothing. */
    public synchronized boolean exhausted(String key, int limit) {
        Window window = windows.get(key);
        return window != null && clock.millis() < window.expiresAt && window.count >= limit;
    }
    private static final class Window {
        final long expiresAt;
        int count;
        Window(long expiresAt) { this.expiresAt = expiresAt; }
    }
}

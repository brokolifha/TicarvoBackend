package com.ticarvo.testsupport;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

/** B-07 (ARCH-M8-01): the HTTP and file helpers every integration test used to copy. */
public final class TestHttp {
    private TestHttp() {}
    /** A session-keeping client: each one is a separate browser. */
    public static HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    /** "status code" from a problem response, or the bare status when there is no problem body. */
    public static String outcome(ObjectMapper json, HttpResponse<String> response) {
        if (response.body() == null || response.body().isBlank()) return Integer.toString(response.statusCode());
        var code = json.readTree(response.body()).get("code");
        return code == null ? Integer.toString(response.statusCode()) : response.statusCode() + " " + code.asString();
    }
    /** Real MySQL tests never skip: a missing setting fails the run. */
    public static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " required; real MySQL tests never skip.");
        return value;
    }
    public static Path tempDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static String read(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}

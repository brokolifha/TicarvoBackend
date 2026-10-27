package com.ticarvo.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

@Component
public class ProblemWriter {
    private final ObjectMapper mapper;
    public ProblemWriter(ObjectMapper mapper) { this.mapper = mapper; }
    public void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), Map.of(
            "type", "about:blank", "title", status.getReasonPhrase(), "status", status.value(), "detail", detail, "code", code));
    }
}

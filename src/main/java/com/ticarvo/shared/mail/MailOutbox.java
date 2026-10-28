package com.ticarvo.shared.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Writes each outgoing mail to a text file (K-19). */
// shortcut: no real delivery, tokens land on disk; replace with a mail provider before VPS release (REL-M4-MAIL).
@Component
public class MailOutbox {
    private final Path directory;
    private final String baseUrl;
    public MailOutbox(@Value("${ticarvo.mail.outbox-dir:}") String directory,
            @Value("${ticarvo.public-base-url:http://localhost:5173}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        if (directory.isBlank()) {
            throw new IllegalStateException("ticarvo.mail.outbox-dir is required until a real mail provider is configured.");
        }
        this.directory = Path.of(directory);
    }
    /** The address of a frontend page; mails link to the page and give the code separately, never in the URL. */
    public String page(String path) { return baseUrl + path; }
    public void send(String to, String subject, String body) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt"),
                "To: " + to + "\nSubject: " + subject + "\n\n" + body + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

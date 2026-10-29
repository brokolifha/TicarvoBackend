package com.ticarvo.shared.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Outgoing mail (ADR-015). With SMTP configured (spring.mail.host) each mail waits in mail_queue until the
 * provider accepts it, with retries; locally and in tests it is written to a text file instead (K-19).
 */
@Component
public class MailOutbox {
    static final int MAX_ATTEMPTS = 10;
    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
    private final Path directory;
    private final String baseUrl;
    private final String from;
    private final MailSender smtp;
    private final JdbcClient db;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public MailOutbox(@Value("${ticarvo.mail.outbox-dir:}") String directory,
            @Value("${ticarvo.public-base-url:http://localhost:5173}") String baseUrl,
            @Value("${ticarvo.mail.from:}") String from, Optional<MailSender> smtp,
            JdbcClient db, PlatformTransactionManager transactions, Clock clock) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.directory = directory.isBlank() ? null : Path.of(directory);
        this.smtp = this.directory == null ? smtp.orElse(null) : null;
        if (this.directory == null && this.smtp == null) {
            throw new IllegalStateException("Set spring.mail.host (SMTP) or, for local use only, ticarvo.mail.outbox-dir.");
        }
        if (this.smtp != null && from.isBlank()) throw new IllegalStateException("ticarvo.mail.from is required with SMTP.");
        this.from = from;
        this.db = db;
        this.clock = clock;
        // Callers send from afterCommit, where the finished transaction is still bound: the queue row needs its own.
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The address of a frontend page; mails link to the page and give the code separately, never in the URL. */
    public String page(String path) { return baseUrl + path; }

    public void send(String to, String subject, String body) {
        if (smtp != null) {
            ownTransaction.executeWithoutResult(status -> db.sql(
                    "INSERT INTO mail_queue(recipient, subject, body, attempts, next_attempt_at) VALUES(?, ?, ?, 0, ?)")
                .params(to, subject, body, now()).update());
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt"),
                "To: " + to + "\nSubject: " + subject + "\n\n" + body + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record Queued(long id, String recipient, String subject, String body, int attempts) {}

    // shortcut: assumes one backend instance; with several, pick rows with SELECT ... FOR UPDATE SKIP LOCKED.
    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void deliver() {
        if (smtp == null) return;
        var due = db.sql("SELECT id, recipient, subject, body, attempts FROM mail_queue WHERE next_attempt_at <= ? ORDER BY id LIMIT 50")
            .param(now()).query(Queued.class).list();
        for (Queued mail : due) {
            try {
                var message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(mail.recipient());
                message.setSubject(mail.subject());
                message.setText(mail.body());
                smtp.send(message);
                db.sql("DELETE FROM mail_queue WHERE id = ?").param(mail.id()).update();
            } catch (MailException failure) {
                int attempts = mail.attempts() + 1;
                // Only the row id and error type are logged: the message may carry the address or a code.
                if (attempts >= MAX_ATTEMPTS) {
                    log.error("Mail {} dropped after {} attempts ({})", mail.id(), attempts, failure.getClass().getSimpleName());
                    db.sql("DELETE FROM mail_queue WHERE id = ?").param(mail.id()).update();
                } else {
                    log.warn("Mail {} failed, attempt {} ({})", mail.id(), attempts, failure.getClass().getSimpleName());
                    db.sql("UPDATE mail_queue SET attempts = ?, next_attempt_at = ? WHERE id = ?")
                        .params(attempts, now().plus(backoff(attempts)), mail.id()).update();
                }
            }
        }
    }

    /** 1, 2, 4 ... 256 minutes: about 8.5 hours in all before a mail is given up. */
    static Duration backoff(int attempts) { return Duration.ofMinutes(1L << (attempts - 1)); }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}

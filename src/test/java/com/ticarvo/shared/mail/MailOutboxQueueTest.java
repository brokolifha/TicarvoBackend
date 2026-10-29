package com.ticarvo.shared.mail;

import com.ticarvo.testsupport.TestHttp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-015: the SMTP path, against the real test MySQL and a fake provider. */
class MailOutboxQueueTest {
    @Test void mailSentAfterCommitIsQueuedDeliveredRetriedAndFinallyDropped() {
        var data = new DriverManagerDataSource(TestHttp.required("TEST_DB_URL"), TestHttp.required("TEST_DB_USERNAME"), TestHttp.required("TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(data).load().migrate();
        var db = JdbcClient.create(data);
        db.sql("DELETE FROM mail_queue").update();
        var transactions = new DataSourceTransactionManager(data);
        List<SimpleMailMessage> sent = new ArrayList<>();
        boolean[] down = {false};
        MailSender provider = messages -> { if (down[0]) throw new MailSendException("provider down"); sent.addAll(List.of(messages)); };
        var outbox = new MailOutbox("", "http://localhost:5173", "noreply@example.invalid", Optional.of(provider), db, transactions, Clock.systemUTC());

        // Sent from afterCommit like every caller: the row must still be committed.
        new TransactionTemplate(transactions).executeWithoutResult(status -> TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() { @Override public void afterCommit() { outbox.send("alici@example.invalid", "Konu ğüş", "Kod: 123456"); } }));
        assertEquals(1, count(db));
        outbox.deliver();
        assertEquals(1, sent.size());
        assertEquals("noreply@example.invalid", sent.getFirst().getFrom());
        assertEquals("Konu ğüş", sent.getFirst().getSubject());
        assertEquals(0, count(db), "a sent mail leaves no copy behind");

        down[0] = true;
        outbox.send("alici@example.invalid", "Tekrar", "Gövde");
        outbox.deliver();
        assertEquals(1, db.sql("SELECT attempts FROM mail_queue").query(Integer.class).single());
        outbox.deliver();
        assertEquals(1, db.sql("SELECT attempts FROM mail_queue").query(Integer.class).single(), "not retried before its time");
        for (int attempt = 2; attempt <= MailOutbox.MAX_ATTEMPTS; attempt++) {
            db.sql("UPDATE mail_queue SET next_attempt_at = '2000-01-01'").update();
            outbox.deliver();
        }
        assertEquals(0, count(db), "given up after the last attempt");
        assertEquals(1, sent.size());
    }

    @Test void backoffDoublesForAboutEightHoursInAll() {
        assertEquals(Duration.ofMinutes(1), MailOutbox.backoff(1));
        assertEquals(Duration.ofMinutes(256), MailOutbox.backoff(MailOutbox.MAX_ATTEMPTS - 1));
    }

    @Test void startupFailsWithoutAnyTransport() {
        var failure = assertThrows(IllegalStateException.class, () -> new MailOutbox("", "x", "", Optional.empty(), null, new DataSourceTransactionManager(), Clock.systemUTC()));
        assertTrue(failure.getMessage().contains("spring.mail.host"));
    }

    private static long count(JdbcClient db) { return db.sql("SELECT COUNT(*) FROM mail_queue").query(Long.class).single(); }
}

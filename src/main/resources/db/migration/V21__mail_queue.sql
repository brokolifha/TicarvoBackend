-- ADR-015 (REL-M4-MAIL): a mail waits here until the SMTP provider accepts it; the row is deleted once sent or given up.
CREATE TABLE mail_queue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient VARCHAR(320) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    INDEX ix_mail_due (next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

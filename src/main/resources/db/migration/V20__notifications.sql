-- ADR-014 (C-04): in-app notifications, one row per recipient so each person has their own read state.
CREATE TABLE notifications (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    company_id CHAR(36) NULL,
    text VARCHAR(300) NOT NULL,
    link VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    INDEX ix_notification_user (user_id, created_at),
    INDEX ix_notification_unread (user_id, read_at),
    INDEX ix_notification_age (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

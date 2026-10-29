package com.ticarvo.notification.service;

import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.notification.dto.NotificationDtos;
import com.ticarvo.notification.entity.Notification;
import com.ticarvo.notification.repository.NotificationRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.validation.PageValidation;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

/**
 * ADR-014 (C-04): in-app notifications. Other modules call notify inside their own transaction, so a notice exists
 * exactly when the change it reports does. The caller names the recipients; this module depends on nobody's data.
 */
@Service
public class Notifications {
    static final Duration KEEP = Duration.ofDays(90);
    private final NotificationRepository notifications;
    private final CurrentUserProvider users;
    private final Clock clock;
    public Notifications(NotificationRepository notifications, CurrentUserProvider users, Clock clock) {
        this.notifications = notifications; this.users = users; this.clock = clock;
    }

    /** One notice per recipient; companyId is the workspace the link belongs to (null for a personal notice). */
    @Transactional
    public void notify(Collection<UUID> recipients, UUID companyId, String text, String link) {
        var now = clock.instant();
        recipients.stream().distinct().forEach(user -> notifications.save(new Notification(user, companyId, text, link, now)));
    }

    @Transactional(readOnly = true)
    public NotificationDtos.Page mine(int page, int size, Authentication auth) {
        PageValidation.validatePage(page, size);
        UUID me = users.currentUserId(auth);
        var result = notifications.findByUserId(me, PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new NotificationDtos.Page(result.map(n -> new NotificationDtos.Item(n.id(), n.text(), n.link(), n.companyId(), n.createdAt(), n.read()))
            .getContent(), page, size, result.getTotalElements(), notifications.countByUserIdAndReadAtIsNull(me));
    }
    @Transactional
    public void markRead(UUID id, Authentication auth) {
        notifications.findByIdAndUserId(id, users.currentUserId(auth))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification was not found."))
            .markRead(clock.instant());
    }
    @Transactional
    public void markAllRead(Authentication auth) { notifications.markAllRead(users.currentUserId(auth), clock.instant()); }

    /** Daily retention: notices are a pointer to the real record, so 90 days is plenty. */
    @Scheduled(cron = "${ticarvo.retention.cron:0 30 3 * * *}")
    @Transactional
    public int removeOld() { return notifications.deleteOlderThan(clock.instant().minus(KEEP)); }
}

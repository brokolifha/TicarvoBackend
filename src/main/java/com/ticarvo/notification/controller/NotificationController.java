package com.ticarvo.notification.controller;

import com.ticarvo.notification.dto.NotificationDtos;
import com.ticarvo.notification.service.Notifications;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** ADR-014: the signed-in person's notifications, newest first, with the unread count. */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
    private final Notifications notifications;
    public NotificationController(Notifications notifications) { this.notifications = notifications; }
    @GetMapping
    public NotificationDtos.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return notifications.mine(page, size, auth);
    }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id, Authentication auth) { notifications.markRead(id, auth); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(Authentication auth) { notifications.markAllRead(auth); }
}

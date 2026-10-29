package com.ticarvo.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}
    public record Item(UUID id, String text, String link, UUID companyId, Instant createdAt, boolean read) {}
    public record Page(List<Item> content, int page, int size, long totalElements, long unread) {}
}

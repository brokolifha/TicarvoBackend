package com.ticarvo.identity.service;

import com.ticarvo.identity.dto.UserSummary;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
/** Narrow identity boundary for other modules: public profile data only, never credentials. */
public interface UserDirectory {
    Map<UUID, UserSummary> summaries(Collection<UUID> userIds);
    /** Row lock inside the caller's transaction; serializes one person's consumer checkouts (ADR-010). */
    void lock(UUID userId);
}

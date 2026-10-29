package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.PendingNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PendingNoticeRepository extends JpaRepository<PendingNotice, UUID> {
    Optional<PendingNotice> findByTokenHash(String tokenHash);
    long countByReporterEmailAndCreatedAtAfter(String email, Instant since);
    @Modifying @Query("delete from PendingNotice n where n.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}

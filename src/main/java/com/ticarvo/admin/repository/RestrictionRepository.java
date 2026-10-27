package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.Restriction;
import com.ticarvo.admin.model.Moderation.TargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface RestrictionRepository extends JpaRepository<Restriction, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restriction r where r.appealTokenHash=:hash")
    Optional<Restriction> lockByAppealTokenHash(@Param("hash") String hash);
    /** Newest first: the head is the target's current state. */
    List<Restriction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(TargetType type, UUID targetId);
}

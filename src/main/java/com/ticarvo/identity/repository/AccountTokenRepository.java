package com.ticarvo.identity.repository;
import com.ticarvo.identity.entity.AccountToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<AccountToken> findForUpdate(@Param("hash") String hash, @Param("purpose") AccountToken.Purpose purpose);
    boolean existsByUserIdAndPurposeAndCreatedAtAfter(UUID userId, AccountToken.Purpose purpose, Instant after);
    @Modifying
    @Query("delete from AccountToken t where t.userId = :user and t.purpose = :purpose")
    void deleteAll(@Param("user") UUID user, @Param("purpose") AccountToken.Purpose purpose);
}

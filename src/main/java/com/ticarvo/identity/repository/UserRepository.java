package com.ticarvo.identity.repository;
import com.ticarvo.identity.entity.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;
public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
    java.util.List<AppUser> findByMfaSecretIsNotNull();
    boolean existsByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.email = :email")
    Optional<AppUser> lockByEmail(@Param("email") String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(@Param("id") UUID id);
    @Query("select u from AppUser u where u.email like :pattern escape '!' or u.displayName like :pattern escape '!'")
    Page<AppUser> search(@Param("pattern") String pattern, Pageable page);
}

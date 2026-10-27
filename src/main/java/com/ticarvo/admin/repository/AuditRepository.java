package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {}

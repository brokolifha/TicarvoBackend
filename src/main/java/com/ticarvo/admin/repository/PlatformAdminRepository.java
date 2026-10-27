package com.ticarvo.admin.repository;

import com.ticarvo.admin.entity.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {}

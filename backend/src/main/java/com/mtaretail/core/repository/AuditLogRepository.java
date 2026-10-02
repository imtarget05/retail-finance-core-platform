package com.mtaretail.core.repository;

import com.mtaretail.core.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(String targetType, String targetId);

    List<AuditLog> findTop50ByActorOrderByOccurredAtDesc(String actor);
}
package com.mtaretail.core.service;

import com.mtaretail.core.domain.AuditLog;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AuditLogRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Records operator actions and, for money movements, joins the posting transaction. */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String actorRole, String action,
                       String targetType, String targetId, String detail) {
        auditLogRepository.save(new AuditLog(actor, actorRole, action, targetType, targetId, detail));
    }

    /** Records an action attributed to the current authenticated principal. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Authentication auth, String action,
                       String targetType, String targetId, String detail) {
        auditLogRepository.save(entry(auth, action, targetType, targetId, detail));
    }

    /** A settled posting and its audit evidence must commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithMovement(Authentication auth, String action,
                                   String targetType, String targetId, String detail) {
        auditLogRepository.saveAndFlush(entry(auth, action, targetType, targetId, detail));
    }

    private AuditLog entry(Authentication auth, String action,
                           String targetType, String targetId, String detail) {
        if (auth == null || !auth.isAuthenticated()) {
            return new AuditLog("system", null, action, targetType, targetId, detail);
        }
        String role = auth.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .orElse(null);
        return new AuditLog(auth.getName(), role, action, targetType, targetId, detail);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> historyFor(String targetType, String targetId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(targetType, targetId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recentByActor(String actor) {
        return auditLogRepository.findTop50ByActorOrderByOccurredAtDesc(actor);
    }

    /** Authority helper for controller checks. */
    public static boolean isAtLeast(Authentication auth, Role required) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .map(Role::valueOf)
                .anyMatch(held -> held.ordinal() >= required.ordinal());
    }
}

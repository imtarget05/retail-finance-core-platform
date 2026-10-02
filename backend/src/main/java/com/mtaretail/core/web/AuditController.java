package com.mtaretail.core.web;

import com.mtaretail.core.domain.AuditLog;
import com.mtaretail.core.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only window onto the audit trail (OPS/ADMIN only, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Who changed what, and when")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Operation(summary = "Full history for one business object, newest first")
    @GetMapping("/target/{targetType}/{targetId}")
    public ResponseEntity<List<Map<String, Object>>> forTarget(@PathVariable String targetType,
                                                              @PathVariable String targetId) {
        return ResponseEntity.ok(auditService.historyFor(targetType, targetId).stream()
                .map(AuditController::toMap)
                .toList());
    }

    @Operation(summary = "Last 50 actions by one actor")
    @GetMapping("/actor/{actor}")
    public ResponseEntity<List<Map<String, Object>>> forActor(@PathVariable String actor) {
        return ResponseEntity.ok(auditService.recentByActor(actor).stream()
                .map(AuditController::toMap)
                .toList());
    }

    private static Map<String, Object> toMap(AuditLog log) {
        return Map.of(
                "id", log.getId(),
                "actor", log.getActor(),
                "actorRole", log.getActorRole() == null ? "" : log.getActorRole(),
                "action", log.getAction(),
                "targetType", log.getTargetType(),
                "targetId", log.getTargetId(),
                "detail", log.getDetail() == null ? "" : log.getDetail(),
                "occurredAt", log.getOccurredAt() == null ? Instant.EPOCH.toString()
                        : log.getOccurredAt().toString());
    }
}
package com.mtaretail.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Append-only audit trail. Written on every state change so an operator can
 * answer "who changed this, when, from where" without touching business rows.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(name = "actor_role", length = 24)
    private String actorRole;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(name = "target_type", nullable = false, length = 48)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLog() {
        // for JPA
    }

    public AuditLog(String actor, String actorRole, String action,
                    String targetType, String targetId, String detail) {
        this.actor = actor;
        this.actorRole = actorRole;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
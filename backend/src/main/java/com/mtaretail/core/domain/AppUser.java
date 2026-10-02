package com.mtaretail.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 128)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, columnDefinition = "VARCHAR(24)")
    private Role role;

    @Column(name = "outlet_id")
    private Long outletId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String passwordHash, String fullName, Role role, Long outletId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.outletId = outletId;
        this.enabled = true;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public Long getOutletId() {
        return outletId;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
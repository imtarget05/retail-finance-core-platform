package com.mtaretail.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "outlet")
public class Outlet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "province_code", nullable = false, length = 16)
    private String provinceCode;

    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Outlet() {
        // for JPA
    }

    public Outlet(String code, String name, String provinceCode) {
        this.code = code;
        this.name = name;
        this.provinceCode = provinceCode;
        this.status = "ACTIVE";
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getProvinceCode() {
        return provinceCode;
    }

    public String getStatus() {
        return status;
    }
}
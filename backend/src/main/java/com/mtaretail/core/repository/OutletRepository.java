package com.mtaretail.core.repository;

import com.mtaretail.core.domain.Outlet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutletRepository extends JpaRepository<Outlet, Long> {

    Optional<Outlet> findByCode(String code);

    boolean existsByCode(String code);
}
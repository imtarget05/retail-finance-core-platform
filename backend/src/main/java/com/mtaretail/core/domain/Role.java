package com.mtaretail.core.domain;

/** RBAC roles. Ordered from least to most privileged for readability. */
public enum Role {
    /** Cashier at a point of sale: can create transactions for own outlet. */
    POS_STAFF,
    /** Outlet manager: sees everything inside one outlet. */
    OUTLET_MANAGER,
    /** Operations: reads across all outlets, triggers reconciliation. */
    OPS,
    /** Administrator: manages users and outlets. */
    ADMIN
}
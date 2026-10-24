package com.mtaretail.core.service;

import com.mtaretail.core.domain.Account;
import com.mtaretail.core.domain.AppUser;
import com.mtaretail.core.domain.Role;
import com.mtaretail.core.repository.AppUserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Reads the current actor and outlet assignment from the database on every request. */
@Service
public class ActorOutletPolicy {
    private final AppUserRepository users;

    public ActorOutletPolicy(AppUserRepository users) {
        this.users = users;
    }

    public AppUser actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw denied();
        }
        AppUser user = users.findByUsername(authentication.getName()).orElseThrow(ActorOutletPolicy::denied);
        if (!user.isEnabled()) {
            throw denied();
        }
        return user;
    }

    public Long requirePostingOutlet(Authentication authentication) {
        AppUser user = actor(authentication);
        if ((user.getRole() != Role.POS_STAFF && user.getRole() != Role.OUTLET_MANAGER)
                || user.getOutletId() == null) {
            throw denied();
        }
        return user.getOutletId();
    }

    public void requireAccountRead(Authentication authentication, Account account) {
        AppUser user = actor(authentication);
        if (user.getRole() != Role.OPS && user.getRole() != Role.ADMIN
                && (user.getOutletId() == null || !Objects.equals(user.getOutletId(), account.getOutletId()))) {
            throw denied();
        }
    }

    public void requirePostingOwnership(Authentication authentication, Account account, Long outletId) {
        Long actorOutlet = requirePostingOutlet(authentication);
        if (!Objects.equals(actorOutlet, outletId)
                || !Objects.equals(actorOutlet, account.getOutletId())) {
            throw denied();
        }
    }

    public void requireTransactionRead(Authentication authentication, Long outletId) {
        AppUser user = actor(authentication);
        if (user.getRole() != Role.OPS && user.getRole() != Role.ADMIN
                && (user.getOutletId() == null || !Objects.equals(user.getOutletId(), outletId))) {
            throw denied();
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Forbidden");
    }
}

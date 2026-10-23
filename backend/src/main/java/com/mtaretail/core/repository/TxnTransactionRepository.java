package com.mtaretail.core.repository;

import com.mtaretail.core.domain.TxnStatus;
import com.mtaretail.core.domain.TxnTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TxnTransactionRepository extends JpaRepository<TxnTransaction, Long> {

    /**
     * Lookup by idempotency key. A retry with the same key must return the
     * original transaction rather than creating a second one.
     */
    Optional<TxnTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<TxnTransaction> findByTxnRef(String txnRef);

    /** Settled transactions in a window - the reconciliation service's feed. */
    @Query("""
            select t from TxnTransaction t
            where t.status = :status
              and t.settledAt >= :from
              and t.settledAt < :to
            order by t.settledAt asc, t.id asc
            """)
    List<TxnTransaction> findSettledBetween(@Param("status") TxnStatus status,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            Pageable pageable);

    long countByStatus(TxnStatus status);
}

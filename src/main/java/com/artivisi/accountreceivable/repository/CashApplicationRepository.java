package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.dto.DebtorCollectionLine;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CashApplicationRepository extends JpaRepository<CashApplication, String> {

    Optional<CashApplication> findByGatewayPaymentReference(String gatewayPaymentReference);

    Page<CashApplication> findByStatus(CashApplicationStatus status, Pageable p);

    long countByStatus(CashApplicationStatus status);

    /** Payments an external check found missing upstream and nobody has cleared yet. */
    @Query("select c from CashApplication c where c.upstreamMissingAt is not null"
            + " and c.upstreamClearedAt is null order by c.upstreamMissingAt desc")
    Page<CashApplication> findUpstreamMissing(Pageable pageable);

    @Query("select count(c) from CashApplication c where c.upstreamMissingAt is not null"
            + " and c.upstreamClearedAt is null")
    long countUpstreamMissing();

    /**
     * Applied allocations against this debtor's single-payment invoices, projected to a DTO so no
     * entity is hydrated.
     *
     * <p>Deliberately split from {@link #findAppliedInstallmentLinesByDebtorCode}. A line targets
     * either an invoice or an installment (never both — {@code chk_cash_line_one_target}), and
     * expressing that as one query with LEFT JOINs and an OR across the two branches forces the
     * planner to scan every allocation row: measured on production, 202 ms and growing with the
     * table. Split, each branch drives debtor → invoice → line off its own index in ~2 ms.
     */
    @Query("""
            select new com.artivisi.accountreceivable.dto.DebtorCollectionLine(
                       ca.receivedAt,
                       ca.gatewayPaymentReference,
                       line.allocatedAmount,
                       cast(null as Integer),
                       inv.dueDate)
              from CashApplicationLine line
              join line.cashApplication ca
              join line.invoice inv
              join inv.debtor debtor
             where ca.status = :applied
               and ca.receivedAt is not null
               and debtor.code = :debtorCode
            """)
    List<DebtorCollectionLine> findAppliedInvoiceLinesByDebtorCode(@Param("debtorCode") String debtorCode,
                                                                   @Param("applied") CashApplicationStatus applied);

    /** The installment half of the ledger: allocation → installment → schedule → invoice → debtor. */
    @Query("""
            select new com.artivisi.accountreceivable.dto.DebtorCollectionLine(
                       ca.receivedAt,
                       ca.gatewayPaymentReference,
                       line.allocatedAmount,
                       inst.sequence,
                       inst.dueDate)
              from CashApplicationLine line
              join line.cashApplication ca
              join line.installment inst
              join inst.schedule sched
              join sched.invoice inv
              join inv.debtor debtor
             where ca.status = :applied
               and ca.receivedAt is not null
               and debtor.code = :debtorCode
            """)
    List<DebtorCollectionLine> findAppliedInstallmentLinesByDebtorCode(@Param("debtorCode") String debtorCode,
                                                                       @Param("applied") CashApplicationStatus applied);

    /** Count of applied cash applications received in a half-open window, for the dashboard. */
    @Query("""
            select count(ca) from CashApplication ca
             where ca.status = :status and ca.receivedAt >= :from and ca.receivedAt < :to
            """)
    long countByStatusReceivedBetween(@Param("status") CashApplicationStatus status,
                                      @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Summed amount over the same window. Null when the window is empty — SQL's sum over no rows —
     * which the caller reads as the empty-sum identity, exactly as the previous accumulate-from-zero
     * loop did.
     */
    @Query("""
            select sum(ca.amount) from CashApplication ca
             where ca.status = :status and ca.receivedAt >= :from and ca.receivedAt < :to
            """)
    java.math.BigDecimal sumAmountByStatusReceivedBetween(@Param("status") CashApplicationStatus status,
                                                          @Param("from") Instant from, @Param("to") Instant to);
}

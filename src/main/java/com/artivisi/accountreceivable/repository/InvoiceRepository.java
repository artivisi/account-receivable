package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.dto.InvoiceListItem;
import com.artivisi.accountreceivable.dto.RecapResponse;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {

    java.util.Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /**
     * The number the campus app was told when the bridge still allocated numbers. Invoices issued
     * before 2026-08-27 15:36 carry it, and an upstream mirror still quotes it, so a lookup that
     * reads only {@code invoiceNumber} cannot find the debt those apps are asking about.
     */
    java.util.Optional<Invoice> findBySourceBillNumber(String sourceBillNumber);

    /** Open receivables (any outstanding balance) for aging / dunning selection. */
    List<Invoice> findByOutstandingGreaterThan(BigDecimal threshold);

    /** Overdue is derived: open balance whose due date has passed. */
    List<Invoice> findByOutstandingGreaterThanAndDueDateBefore(BigDecimal threshold, LocalDate date);

    List<Invoice> findByDebtorCodeOrderByIssueDateAsc(String debtorCode);

    /**
     * Credit sales issued in an inclusive date window, for the dashboard's DSO denominator. Null when
     * the window holds no invoice — SQL's sum over no rows — which the caller reads as the empty-sum
     * identity, exactly as the accumulate-from-zero loop it replaced did.
     */
    @Query("select sum(i.amount) from Invoice i where i.issueDate >= :from and i.issueDate <= :to")
    BigDecimal sumAmountIssuedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Receivables recap grouped by invoice type — counts and sums in the database, one row per type. */
    @Query("""
            select new com.artivisi.accountreceivable.dto.RecapResponse$TypeLine(
                       t.code, count(i), sum(i.amount), sum(i.outstanding))
              from Invoice i
              join i.invoiceType t
             group by t.code
             order by t.code
            """)
    List<RecapResponse.TypeLine> recapByInvoiceType();

    /**
     * Paginated invoice-list search — filters + sort + LIMIT/OFFSET all in the database, projecting
     * only the columns the list needs (no line/installment/entity hydration). Overdue is expressed
     * against the denormalized {@code earliestUnpaidDueDate} column. Optional filters are null-guarded:
     * {@code overdueOnly=false}, {@code status=null}, {@code typeCode=null}, {@code q=null} each disable
     * their clause. {@code q} must already be lower-cased and wrapped in {@code %...%} by the caller.
     * {@code bucketFrom}/{@code bucketTo} bound the due date so an aging band can be drilled into
     * from the provision report.
     */
    @Query(value = """
            select new com.artivisi.accountreceivable.dto.InvoiceListItem(
                i.id, i.invoiceNumber, i.debtor.code, i.invoiceType.code,
                i.dueDate, i.amount, i.outstanding, i.paymentStatus, i.earliestUnpaidDueDate)
            from Invoice i
            where (:overdueOnly = false or (i.earliestUnpaidDueDate is not null and i.earliestUnpaidDueDate < :today))
              and (:status is null or i.paymentStatus = :status)
              and (:typeCode is null or i.invoiceType.code = :typeCode)
              and (:q is null or lower(i.invoiceNumber) like :q or lower(i.debtor.code) like :q)
              and (:bucketFrom is null or (i.outstanding > 0 and i.dueDate <= :bucketFrom))
              and (:bucketTo is null or (i.outstanding > 0 and i.dueDate >= :bucketTo))
            """,
            countQuery = """
            select count(i)
            from Invoice i
            where (:overdueOnly = false or (i.earliestUnpaidDueDate is not null and i.earliestUnpaidDueDate < :today))
              and (:status is null or i.paymentStatus = :status)
              and (:typeCode is null or i.invoiceType.code = :typeCode)
              and (:q is null or lower(i.invoiceNumber) like :q or lower(i.debtor.code) like :q)
              and (:bucketFrom is null or (i.outstanding > 0 and i.dueDate <= :bucketFrom))
              and (:bucketTo is null or (i.outstanding > 0 and i.dueDate >= :bucketTo))
            """)
    Page<InvoiceListItem> search(@Param("overdueOnly") boolean overdueOnly,
                                 @Param("status") PaymentStatus status,
                                 @Param("typeCode") String typeCode,
                                 @Param("q") String q,
                                 // Aging band as a due-date window: dueDate <= bucketFrom means "at
                                 // least this old", dueDate >= bucketTo means "no older than". Either
                                 // may be null, which disables that edge — that is how CURRENT
                                 // (not yet due) and DUE_90_PLUS (open-ended) are expressed.
                                 @Param("bucketFrom") LocalDate bucketFrom,
                                 @Param("bucketTo") LocalDate bucketTo,
                                 @Param("today") LocalDate today,
                                 Pageable pageable);
}

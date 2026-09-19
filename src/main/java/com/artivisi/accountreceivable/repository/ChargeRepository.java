package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.dto.ChargeListItem;
import com.artivisi.accountreceivable.entity.Charge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, String> {

    Optional<Charge> findByConsumerReference(String consumerReference);

    /** The gateway's own id for this charge — unique, and how inbound gateway events address us. */
    Optional<Charge> findByGatewayChargeId(String gatewayChargeId);

    /**
     * Paginated charge-list search — projection + LIMIT/OFFSET in the database, no entity
     * hydration (the target chain invoice / installment→schedule→invoice→debtor is all LAZY and
     * would N+1 otherwise). The coalesce pairs resolve the charge's single target (invoice XOR
     * installment, per {@code chk_charge_one_target}). {@code q} must already be lower-cased and
     * wrapped in {@code %...%} by the caller; it matches invoice number, debtor name, VA number,
     * gateway charge id, and consumer reference. Order-by is fixed in the query (newest first) —
     * pass an unsorted {@code Pageable}; a {@code Sort} would append a second order-by.
     */
    @Query(value = """
            select new com.artivisi.accountreceivable.dto.ChargeListItem(
                c.createdAt, c.gatewayChargeId,
                coalesce(inv.id, sinv.id), coalesce(inv.invoiceNumber, sinv.invoiceNumber),
                inst.sequence, sch.installmentCount,
                coalesce(invd.name, sinvd.name),
                c.vaNumber, c.amount, c.cumulativePaid, c.status)
            from Charge c
            left join c.invoice inv
            left join inv.debtor invd
            left join c.installment inst
            left join inst.schedule sch
            left join sch.invoice sinv
            left join sinv.debtor sinvd
            where (:q is null
                or lower(c.gatewayChargeId) like :q
                or lower(c.consumerReference) like :q
                or lower(c.vaNumber) like :q
                or lower(coalesce(inv.invoiceNumber, sinv.invoiceNumber)) like :q
                or lower(coalesce(invd.name, sinvd.name)) like :q)
            order by c.createdAt desc
            """,
            countQuery = """
            select count(c)
            from Charge c
            left join c.invoice inv
            left join inv.debtor invd
            left join c.installment inst
            left join inst.schedule sch
            left join sch.invoice sinv
            left join sinv.debtor sinvd
            where (:q is null
                or lower(c.gatewayChargeId) like :q
                or lower(c.consumerReference) like :q
                or lower(c.vaNumber) like :q
                or lower(coalesce(inv.invoiceNumber, sinv.invoiceNumber)) like :q
                or lower(coalesce(invd.name, sinvd.name)) like :q)
            """)
    Page<ChargeListItem> search(@Param("q") String q, Pageable pageable);

    /** Charges currently occupying a VA number — at most the still-collectible ones supersede. */
    java.util.List<Charge> findByVaNumberAndStatusIn(
            String vaNumber, java.util.Collection<com.artivisi.accountreceivable.entity.ChargeStatus> statuses);


    /** Charges opened against one invoice, in any state. */
    java.util.List<Charge> findByInvoiceId(String invoiceId);

    /** The live charge of every instalment plan — the ones whose amount must track the plan. */
    @Query("""
            select c from Charge c
            where c.status = com.artivisi.accountreceivable.entity.ChargeStatus.ACTIVE
              and c.invoice is not null
              and c.invoice.installment = true
            """)
    java.util.List<Charge> findActivePlanCharges();

    /** Charges opened against one installment, in any state. */
    java.util.List<Charge> findByInstallmentId(String installmentId);

    /**
     * Receivables whose collection was retired so a newer bill could take their VA number, and whose
     * number is now free again — the newer bill has been paid, cancelled or withdrawn. Legacy
     * collects successive bills of a debtor on one number, so these are the queue behind it: still
     * owed, and now collectible again. Oldest supersession first, which is the order legacy issued
     * them in.
     *
     * <p>ACTIVE and PARTIALLY_PAID hold the number; PAID does not. A fully paid charge has had its VA
     * retired at the gateway, which is precisely the moment the receivable behind it can be put back.
     *
     * <p>Cancelled-and-superseded only. A charge cancelled for any other reason is not eligible: a
     * write-off means the debt is forgiven, and a cancellation the gateway reported is a decision for
     * a human. See {@link com.artivisi.accountreceivable.entity.Charge#supersede}.
     */
    @Query("""
            select c from Charge c
            where c.status = com.artivisi.accountreceivable.entity.ChargeStatus.CANCELLED
              and c.supersededAt is not null
              and not exists (
                  select 1 from Charge holder
                  where holder.vaNumber = c.vaNumber
                    and holder.status in (
                        com.artivisi.accountreceivable.entity.ChargeStatus.ACTIVE,
                        com.artivisi.accountreceivable.entity.ChargeStatus.PARTIALLY_PAID)
              )
            order by c.supersededAt asc
            """)
    java.util.List<Charge> findSupersededWithFreeVa();
}

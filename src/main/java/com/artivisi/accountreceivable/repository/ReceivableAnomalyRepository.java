package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.ReceivableAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReceivableAnomalyRepository extends JpaRepository<ReceivableAnomaly, String> {

    /**
     * The idempotency lookup, matching {@code uq_receivable_anomaly_finding}. A daily check
     * re-reporting an unresolved finding must land on the existing row rather than a second one.
     */
    Optional<ReceivableAnomaly> findByInvoiceIdAndCategoryAndEvidenceRef(
            String invoiceId, String category, String evidenceRef);

    List<ReceivableAnomaly> findByResolvedAtIsNullOrderByCreatedAtDesc();

    List<ReceivableAnomaly> findByInvoiceIdOrderByCreatedAtDesc(String invoiceId);

    /**
     * The worklist. Invoice and debtor are fetched with the finding because open-in-view is off and
     * every row renders both.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoice", "invoice.debtor"})
    org.springframework.data.domain.Page<ReceivableAnomaly> findByResolvedAtIsNull(
            org.springframework.data.domain.Pageable pageable);

    long countByResolvedAtIsNull();

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoice", "invoice.debtor"})
    List<ReceivableAnomaly> findByInvoiceIdAndResolvedAtIsNullOrderByCreatedAtAsc(String invoiceId);
}

package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One receivable awaiting a collectibility decision, with the evidence needed to take it.
 *
 * <p>The queue exists because "this can no longer be collected" and "this debt is forgiven" are
 * different statements, and only a person may make the second. Each row therefore carries what the
 * ledger can prove rather than a conclusion, and {@link #verdict()} labels the shape of the evidence
 * — never the decision.
 */
public record ReviewQueueItem(
        String invoiceId,
        String invoiceNumber,
        String sourceBillNumber,
        String debtorCode,
        String debtorName,
        String description,
        LocalDate dueDate,
        BigDecimal outstanding,
        String vaNumber,
        Instant cancelledAt,
        Instant withdrawnAt,
        String withdrawnReason,
        boolean everCharged,
        long cashApplications,
        BigDecimal paidOnSameVaNumber,
        String paidSiblingBill
) {

    /** What kind of evidence this row carries. Ordered by how much a reviewer must add to it. */
    public enum Verdict {
        /** A later charge on the same VA number was paid — quite possibly this debt, settled under
         *  a replacement bill. Needs confirming against the payment, never assumed: VA numbers are
         *  reused across successive bills, so the payment may belong to another period entirely. */
        POSSIBLY_SETTLED,
        /** No charge was ever opened, so there is no billing or payment trail here at all. Absence
         *  of a trail is not evidence the debt is gone — only that it was never billed by VA. */
        NEVER_BILLED,
        /** Every charge is dead and nothing was ever received. Writing this off moves only the
         *  ledger: no VA answers, so nothing becomes more or less collectible either way. */
        UNCOLLECTIBLE
    }

    public Verdict verdict() {
        if (paidOnSameVaNumber != null && paidOnSameVaNumber.signum() > 0) {
            return Verdict.POSSIBLY_SETTLED;
        }
        return everCharged ? Verdict.UNCOLLECTIBLE : Verdict.NEVER_BILLED;
    }

    /** True when the originating system, rather than a dead VA, is what put this row here. */
    public boolean withdrawnUpstream() {
        return withdrawnAt != null;
    }
}

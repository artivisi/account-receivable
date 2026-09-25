package com.artivisi.accountreceivable.entity;

/**
 * Why an issued invoice is worth less than it says. The distinction is an accounting one, not a
 * label: a scholarship is a real debt settled by someone other than the payer, while a discount or a
 * correction means the debt itself was never that large.
 */
public enum CreditReason {

    /**
     * A third party — the institution, a sponsor — covers part or all of what the payer owes. The
     * receivable was correct; who settles it is what changed. Requires the decision it rests on.
     */
    SCHOLARSHIP,

    /** The price was reduced after issue: a promotion, a referral code, a negotiated rate. */
    DISCOUNT,

    /** The invoice was wrong — wrong rate, wrong quantity, billed twice for one thing. */
    CORRECTION
}

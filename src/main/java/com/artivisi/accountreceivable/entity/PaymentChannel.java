package com.artivisi.accountreceivable.entity;

/**
 * How a payment outside the Payment Gateway reached the institution. Applies to
 * {@link PaymentSource#RECORDED} only — a gateway payment arrived through a virtual account, and
 * which bank rail carried it is the escrow's business, not a channel.
 *
 * <p>There is deliberately no catch-all value. Cash receipts are reported by channel, and a bucket
 * labelled "other" is the one line of that report nobody can act on: once it exists every channel
 * nobody has got round to adding lands in it. A channel that is not here is refused, which is a
 * request for a contract revision rather than an unanalysable row.
 */
public enum PaymentChannel {

    /** Money handed over at a counter. */
    CASH,

    /** A transfer made by the payer straight to an institution account, outside any VA. */
    TRANSFER,

    /** Scanned through a QRIS acquirer. */
    QRIS,

    /** A card tapped or swiped on a terminal at a counter. */
    EDC
}

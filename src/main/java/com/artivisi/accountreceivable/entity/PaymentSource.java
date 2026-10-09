package com.artivisi.accountreceivable.entity;

/**
 * Who issued a payment's reference, and therefore which namespace it is unique within.
 *
 * <p>A bank journal number and an upstream application's receipt number are issued by different
 * parties and can collide by coincidence. Scoping uniqueness to the source keeps that coincidence
 * from being mistaken for a replayed webhook, which would discard the second payment silently.
 */
public enum PaymentSource {

    /** The bank, reaching AR through the gateway's payment webhook. */
    GATEWAY,

    /**
     * An upstream application, through {@code payment.recorded} — cash at a counter, a direct
     * transfer, or a channel outside the gateway such as QRIS. The gateway never saw the money, so
     * no VA was settled and no charge is closed by it.
     */
    RECORDED
}

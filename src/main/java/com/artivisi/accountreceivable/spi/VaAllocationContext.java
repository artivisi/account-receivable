package com.artivisi.accountreceivable.spi;

/**
 * Context passed to {@link VaKeySupplier#keys} when opening a gateway charge. No escrow: the keys
 * are the same whichever bank the charge is paid through.
 */
public record VaAllocationContext(
        String consumerReference,
        String debtorCode,
        String invoiceTypeCode
) {
}

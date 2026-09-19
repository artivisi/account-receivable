package com.artivisi.accountreceivable.spi;

/** Context passed to {@link VaNumberSupplier#allocate} when opening a gateway charge. */
public record VaAllocationContext(
        String escrowCode,
        String consumerReference,
        String debtorCode,
        String invoiceTypeCode
) {
}

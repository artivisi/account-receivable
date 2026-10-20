package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.spi.VaAllocationContext;
import com.artivisi.accountreceivable.spi.VaKeySupplier;
import com.artivisi.accountreceivable.spi.VaKeys;

/**
 * The payer is the debtor, and the slot is the invoice type's VA code: one live bill per debtor and
 * type, which is the shape every upstream application already bills in.
 *
 * <p>The invoice type → VA code mapping stays in AR because invoice types are AR's vocabulary. The
 * gateway only ever sees the code, never what it means.
 *
 * <p>An invoice type with no mapping is refused as {@link InvalidRequestException}, not an illegal
 * state: it is a fact about what was asked for, and over the contract the difference is whether
 * the sender is answered with {@code invoice.rejected} or with nothing at all.
 *
 * <p>Registered as the default {@link VaKeySupplier} bean when no override is provided.
 */
public class InvoiceTypeVaKeySupplier implements VaKeySupplier {

    private final InvoiceTypeVaCodeRepository vaCodeRepository;

    public InvoiceTypeVaKeySupplier(InvoiceTypeVaCodeRepository vaCodeRepository) {
        this.vaCodeRepository = vaCodeRepository;
    }

    @Override
    public VaKeys keys(VaAllocationContext ctx) {
        String slotKey = vaCodeRepository.findByInvoiceTypeCode(ctx.invoiceTypeCode())
                .map(m -> m.getVaCode())
                .orElseThrow(() -> new InvalidRequestException("INVOICE_TYPE_UNKNOWN",
                        "No VA code mapping for invoice type: " + ctx.invoiceTypeCode()));
        return new VaKeys(ctx.debtorCode(), slotKey);
    }
}

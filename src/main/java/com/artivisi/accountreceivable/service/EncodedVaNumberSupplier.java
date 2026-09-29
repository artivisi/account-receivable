package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.spi.VaAllocationContext;
import com.artivisi.accountreceivable.spi.VaNumberSupplier;

/**
 * Config-driven VA number encoder: {@code prefix + leftPad(typeCode, N) + rightPad(debtorCode, M)}.
 * Requires {@code ar.gateway.va-prefix}, {@code ar.gateway.va-digit-length}, and
 * {@code ar.gateway.va-invoice-type-digits} to be set. Fail loud if the invoice type has no
 * mapping or the debtor code overflows the available digits.
 *
 * <p>The debtor segment is zero-filled on the <em>right</em>: the code occupies the leading
 * positions of a fixed-width field, so a debtor code shorter than the field still yields the same
 * number the bank already knows. Left-filling instead would mint a different VA for every
 * short-code debtor (e.g. debtor {@code 18200417} → {@code 16 1820041700}, not
 * {@code 16 0018200417}).
 *
 * <p>Registered as the default {@link VaNumberSupplier} bean when no override is provided.
 */
public class EncodedVaNumberSupplier implements VaNumberSupplier {

    private final String vaPrefix;
    private final int vaDigitLength;
    private final int invoiceTypeDigits;
    private final int debtorDigits;
    private final InvoiceTypeVaCodeRepository vaCodeRepository;

    public EncodedVaNumberSupplier(ArGatewayProperties properties,
                             InvoiceTypeVaCodeRepository vaCodeRepository) {
        this.vaPrefix = properties.vaPrefix();
        this.vaDigitLength = properties.vaDigitLength();
        this.invoiceTypeDigits = properties.vaInvoiceTypeDigits();
        this.debtorDigits = vaDigitLength - vaPrefix.length() - invoiceTypeDigits;
        if (debtorDigits <= 0) {
            throw new IllegalStateException(
                    "VA digit layout leaves no room for debtor code: prefix=" + vaPrefix.length()
                            + " typeDigits=" + invoiceTypeDigits + " total=" + vaDigitLength);
        }
        this.vaCodeRepository = vaCodeRepository;
    }

    /**
     * Refusals here are {@link InvalidRequestException}, not {@link IllegalStateException}.
     *
     * <p>They describe the request, not a broken deployment: a debtor code too long for the number
     * space and an invoice type with no VA code are both facts about what was asked for, and asking
     * again changes nothing. Thrown as an illegal state they surfaced as an internal failure, which
     * over the contract means the sender is answered with nothing at all — on 2026-09-29 three of
     * SPMB's test commands disappeared exactly this way, with their listener left waiting for an
     * {@code invoice.rejected} that was never going to arrive. Silence is the one answer a command
     * may never get.
     */
    @Override
    public String allocate(VaAllocationContext ctx) {
        String typeNum = vaCodeRepository.findByInvoiceTypeCode(ctx.invoiceTypeCode())
                .map(m -> m.getVaCode())
                .orElseThrow(() -> new InvalidRequestException("INVOICE_TYPE_UNKNOWN",
                        "No VA code mapping for invoice type: " + ctx.invoiceTypeCode()));

        String debtorCode = ctx.debtorCode();
        if (debtorCode.length() > debtorDigits) {
            throw new InvalidRequestException("DEBTOR_CODE_UNENCODABLE",
                    "Debtor code '" + debtorCode + "' (" + debtorCode.length()
                            + " chars) does not fit the " + debtorDigits + " digits this deployment's"
                            + " VA number space leaves for it");
        }

        return vaPrefix
                + leftPad(typeNum, invoiceTypeDigits)
                + rightPad(debtorCode, debtorDigits);
    }

    private static String leftPad(String value, int width) {
        if (value.length() >= width) return value;
        return "0".repeat(width - value.length()) + value;
    }

    private static String rightPad(String value, int width) {
        if (value.length() >= width) return value;
        return value + "0".repeat(width - value.length());
    }
}

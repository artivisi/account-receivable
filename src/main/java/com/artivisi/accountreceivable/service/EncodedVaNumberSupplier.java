package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
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

    @Override
    public String allocate(VaAllocationContext ctx) {
        String typeNum = vaCodeRepository.findByInvoiceTypeCode(ctx.invoiceTypeCode())
                .map(m -> m.getVaCode())
                .orElseThrow(() -> new IllegalStateException(
                        "No VA code mapping for invoice type: " + ctx.invoiceTypeCode()));

        String debtorCode = ctx.debtorCode();
        if (debtorCode.length() > debtorDigits) {
            throw new IllegalStateException(
                    "Debtor code '" + debtorCode + "' (" + debtorCode.length()
                            + " chars) exceeds available VA digits (" + debtorDigits + ")");
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

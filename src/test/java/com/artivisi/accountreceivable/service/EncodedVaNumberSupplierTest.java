package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.spi.VaAllocationContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import com.artivisi.accountreceivable.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncodedVaNumberSupplierTest {

    private static ArGatewayProperties props(String prefix, int total, int typeDigits) {
        return new ArGatewayProperties("http://gw", "id", "secret", "escrow",
                3, 0, 3600000, prefix, total, typeDigits);
    }

    private static InvoiceTypeVaCodeRepository repoReturning(String invoiceTypeCode, String vaCode) {
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        InvoiceTypeVaCode mapping = mock(InvoiceTypeVaCode.class);
        when(mapping.getVaCode()).thenReturn(vaCode);
        when(repo.findByInvoiceTypeCode(invoiceTypeCode)).thenReturn(Optional.of(mapping));
        return repo;
    }

    @Test
    void encodes_prefix_typeCode_debtorCode() {
        var repo = repoReturning("SPP", "1");
        var supplier = new EncodedVaNumberSupplier(props("88801", 16, 2), repo);
        // prefix=5, typeDigits=2, debtorDigits=9
        String va = supplier.allocate(new VaAllocationContext("escrow", "ref", "12345", "SPP"));
        assertThat(va).isEqualTo("88801" + "01" + "123450000");
        assertThat(va).hasSize(16);
    }

    /**
     * A short debtor code fills the leading positions of the debtor field, zero-filled on the
     * right — the number the bank already carries. Filling on the left would mint a VA nobody
     * has ever seen (regression: debtor 18200417 got 16-0018200417 instead of 16-1820041700,
     * so a real payment could not be matched).
     */
    @Test
    void short_debtor_code_is_zero_filled_on_the_right() {
        var repo = repoReturning("COURSE", "16");
        var supplier = new EncodedVaNumberSupplier(props("", 12, 2), repo);
        // prefix=0, typeDigits=2, debtorDigits=10
        String va = supplier.allocate(new VaAllocationContext("escrow", "ref", "18200417", "COURSE"));
        assertThat(va).isEqualTo("16" + "1820041700");
    }

    @Test
    void full_width_debtor_code_is_unchanged() {
        var repo = repoReturning("REG", "01");
        var supplier = new EncodedVaNumberSupplier(props("", 12, 2), repo);
        String va = supplier.allocate(new VaAllocationContext("escrow", "ref", "2026990031", "REG"));
        assertThat(va).isEqualTo("01" + "2026990031");
    }

    @Test
    void constrained_one_digit_type() {
        var repo = repoReturning("REG", "2");
        var supplier = new EncodedVaNumberSupplier(props("12345678901", 16, 1), repo);
        // prefix=11, typeDigits=1, debtorDigits=4
        String va = supplier.allocate(new VaAllocationContext("escrow", "ref", "9999", "REG"));
        assertThat(va).isEqualTo("12345678901" + "2" + "9999");
        assertThat(va).hasSize(16);
    }

    @Test
    void fails_when_invoice_type_has_no_mapping() {
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        when(repo.findByInvoiceTypeCode("UNKNOWN")).thenReturn(Optional.empty());
        var supplier = new EncodedVaNumberSupplier(props("88801", 16, 2), repo);

        assertThatThrownBy(() ->
                supplier.allocate(new VaAllocationContext("escrow", "ref", "123", "UNKNOWN")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("No VA code mapping for invoice type: UNKNOWN")
                .extracting(e -> ((InvalidRequestException) e).getCode())
                .isEqualTo("INVOICE_TYPE_UNKNOWN");
    }

    @Test
    void fails_when_debtor_code_overflows() {
        var repo = repoReturning("SPP", "1");
        var supplier = new EncodedVaNumberSupplier(props("88801", 16, 2), repo);
        // debtorDigits=9; code has 10 chars
        // An InvalidRequestException with a code, not an IllegalStateException: over the contract the
        // difference is whether the sender is answered with invoice.rejected or with nothing at all.
        // Three of SPMB's dev commands vanished this way on 2026-09-29.
        assertThatThrownBy(() ->
                supplier.allocate(new VaAllocationContext("escrow", "ref", "1234567890", "SPP")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not fit the 9 digits")
                .extracting(e -> ((InvalidRequestException) e).getCode())
                .isEqualTo("DEBTOR_CODE_UNENCODABLE");
    }

    @Test
    void fails_at_construction_when_layout_leaves_no_debtor_room() {
        var repo = mock(InvoiceTypeVaCodeRepository.class);
        // prefix=14, typeDigits=2, total=15 → debtorDigits=-1
        assertThatThrownBy(() -> new EncodedVaNumberSupplier(props("88801234567890", 15, 2), repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no room for debtor code");
    }
}

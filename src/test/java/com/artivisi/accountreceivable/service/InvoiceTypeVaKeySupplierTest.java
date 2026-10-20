package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.spi.VaAllocationContext;
import com.artivisi.accountreceivable.spi.VaKeys;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The keys only. How they become a number — padding, segment widths, prefix — is the gateway's,
 * and is tested there against the escrow's layout.
 */
class InvoiceTypeVaKeySupplierTest {

    private static InvoiceTypeVaCodeRepository repoReturning(String invoiceTypeCode, String vaCode) {
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        InvoiceTypeVaCode mapping = mock(InvoiceTypeVaCode.class);
        when(mapping.getVaCode()).thenReturn(vaCode);
        when(repo.findByInvoiceTypeCode(invoiceTypeCode)).thenReturn(Optional.of(mapping));
        return repo;
    }

    @Test
    void thePayerIsTheDebtor_andTheSlotIsTheTypesVaCode() {
        var supplier = new InvoiceTypeVaKeySupplier(repoReturning("COURSE", "16"));

        VaKeys keys = supplier.keys(new VaAllocationContext("ref", "18200417", "COURSE"));

        // Sent as they are, unpadded: the width each segment gets belongs to the escrow.
        assertThat(keys).isEqualTo(new VaKeys("18200417", "16"));
    }

    @Test
    void anInvoiceTypeWithNoVaCode_isRefusedAsTheRequestsFault() {
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        when(repo.findByInvoiceTypeCode("UNKNOWN")).thenReturn(Optional.empty());
        var supplier = new InvoiceTypeVaKeySupplier(repo);

        assertThatThrownBy(() -> supplier.keys(new VaAllocationContext("ref", "123", "UNKNOWN")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("No VA code mapping for invoice type: UNKNOWN")
                .extracting(e -> ((InvalidRequestException) e).getCode())
                .isEqualTo("INVOICE_TYPE_UNKNOWN");
    }
}

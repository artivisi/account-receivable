package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;

public record InvoiceTypeVaCodeResponse(
        String id,
        String invoiceTypeId,
        String invoiceTypeCode,
        String vaCode
) {
    public static InvoiceTypeVaCodeResponse from(InvoiceTypeVaCode m) {
        return new InvoiceTypeVaCodeResponse(
                m.getId(),
                m.getInvoiceType().getId(),
                m.getInvoiceType().getCode(),
                m.getVaCode());
    }
}

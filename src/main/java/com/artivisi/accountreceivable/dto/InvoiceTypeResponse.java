package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.InvoiceType;

import java.time.Instant;

public record InvoiceTypeResponse(
        String id,
        String code,
        String name,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static InvoiceTypeResponse from(InvoiceType t) {
        return new InvoiceTypeResponse(
                t.getId(), t.getCode(), t.getName(),
                t.isActive(), t.getCreatedAt(), t.getUpdatedAt());
    }
}

package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.DebtorStatus;

import java.time.Instant;

public record DebtorResponse(
        String id,
        String code,
        String name,
        String email,
        String phone,
        DebtorStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static DebtorResponse from(Debtor d) {
        return new DebtorResponse(
                d.getId(), d.getCode(), d.getName(), d.getEmail(), d.getPhone(),
                d.getStatus(), d.getCreatedAt(), d.getUpdatedAt());
    }
}

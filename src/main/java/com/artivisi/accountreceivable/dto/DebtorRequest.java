package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.DebtorStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DebtorRequest(
        @NotBlank String code,
        @NotBlank String name,
        String email,
        String phone,
        @NotNull DebtorStatus status
) {
}

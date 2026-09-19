package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.ChargeType;

import java.math.BigDecimal;

public record ChargeResponse(
        String id,
        String gatewayChargeId,
        String consumerReference,
        ChargeType chargeType,
        String invoiceId,
        String installmentId,
        BigDecimal amount,
        BigDecimal cumulativePaid,
        String currency,
        ChargeStatus status,
        String escrowCode,
        String vaNumber
) {
    public static ChargeResponse from(Charge c) {
        return new ChargeResponse(
                c.getId(), c.getGatewayChargeId(), c.getConsumerReference(), c.getChargeType(),
                c.getInvoice() == null ? null : c.getInvoice().getId(),
                c.getInstallment() == null ? null : c.getInstallment().getId(),
                c.getAmount(), c.getCumulativePaid(), c.getCurrency(), c.getStatus(),
                c.getEscrowCode(), c.getVaNumber());
    }
}

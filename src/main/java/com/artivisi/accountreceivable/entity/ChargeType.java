package com.artivisi.accountreceivable.entity;

/**
 * Mirrors the gateway charge type. AR opens CLOSED charges only; an instalment is one CLOSED charge
 * repriced over time, which is why neither AR nor the gateway has an INSTALLMENT type.
 */
public enum ChargeType {
    CLOSED,
    OPEN
}

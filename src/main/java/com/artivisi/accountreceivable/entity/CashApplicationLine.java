package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Allocation of a {@link CashApplication} to one invoice or one installment. */
@Getter
@Setter
@Entity
@Table(name = "cash_application_line")
public class CashApplicationLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_cash_application")
    private CashApplication cashApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_installment")
    private Installment installment;

    private BigDecimal allocatedAmount;
}

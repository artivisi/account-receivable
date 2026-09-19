package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A correction that reduces an issued invoice's outstanding, with its own reversing journal. */
@Getter
@Setter
@Entity
@Table(name = "credit_note")
public class CreditNote extends BaseEntity {

    private String creditNoteNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_debtor")
    private Debtor debtor;

    private LocalDate issueDate;

    private String currency;

    private BigDecimal amount;

    private String reason;
}

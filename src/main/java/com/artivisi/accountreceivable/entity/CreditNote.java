package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** What kind of correction this is. NULL only on notes issued before the kind was recorded. */
    @Enumerated(EnumType.STRING)
    private CreditReason reasonCode;

    /** The decision this credit rests on: a scholarship decree, an approval, a ticket. */
    private String reference;

    /** Free text for the reviewer, beside the kind. */
    private String reason;
}

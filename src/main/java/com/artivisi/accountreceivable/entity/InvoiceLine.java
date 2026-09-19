package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** A line item. {@code lineAmount} = quantity × unitAmount; the lines sum to the invoice amount. */
@Getter
@Setter
@Entity
@Table(name = "invoice_line")
public class InvoiceLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    private int lineNo;

    private String description;

    private BigDecimal quantity;

    private BigDecimal unitAmount;

    private BigDecimal lineAmount;
}

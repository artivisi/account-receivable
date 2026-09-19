package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Maps one {@link InvoiceType} to a short numeric VA code used in VA number encoding. */
@Getter
@Setter
@Entity
@Table(name = "invoice_type_va_code")
public class InvoiceTypeVaCode extends BaseEntity {

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "id_invoice_type", nullable = false)
    private InvoiceType invoiceType;

    private String vaCode;
}

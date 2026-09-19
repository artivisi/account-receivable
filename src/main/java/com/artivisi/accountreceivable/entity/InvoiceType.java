package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Receivable category. Carries the GL JournalTemplate id used to post this type's issue journal
 * (the template bakes in Dr A/R control · Cr [this type's revenue]); AR resolves no accounts.
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_type")
public class InvoiceType extends BaseEntity {

    private String code;

    private String name;

    private boolean active;
}

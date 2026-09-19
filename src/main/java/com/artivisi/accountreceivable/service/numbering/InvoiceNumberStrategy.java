package com.artivisi.accountreceivable.service.numbering;

import com.artivisi.accountreceivable.entity.InvoiceType;

import java.time.LocalDate;

/**
 * How an invoice gets the number people outside this system will quote at it.
 *
 * <p>The guarantee is the product's and does not vary: one issuer, allocated inside the same
 * transaction as the invoice it identifies, so two issuers can never mint the same number for
 * different debts. The <em>format</em> is a deployment's own business — an institution that has been
 * printing a particular shape of number on its bills for years does not get to change it because it
 * changed billing systems.
 *
 * <p>That split is the whole point of this interface. Anything deployment-specific belongs in an
 * implementation and its configuration, never in the invoice service.
 */
public interface InvoiceNumberStrategy {

    /**
     * @param type      the invoice's type — some formats encode it
     * @param issueDate the date the invoice is issued under, which dated formats key their sequence on
     */
    String next(InvoiceType type, LocalDate issueDate);
}

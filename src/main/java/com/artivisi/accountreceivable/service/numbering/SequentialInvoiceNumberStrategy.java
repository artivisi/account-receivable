package com.artivisi.accountreceivable.service.numbering;

import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.service.RunningNumberService;

import java.time.LocalDate;

/**
 * The product default: a configured prefix and one ever-increasing sequence, e.g. {@code INV000123}.
 * Neither the date nor the type appears, so a deployment with no external numbering convention to
 * honour gets numbers that are simply unique and readable.
 */
public class SequentialInvoiceNumberStrategy implements InvoiceNumberStrategy {

    private final RunningNumberService runningNumbers;
    private final ArInvoiceProperties properties;

    public SequentialInvoiceNumberStrategy(RunningNumberService runningNumbers,
                                           ArInvoiceProperties properties) {
        this.runningNumbers = runningNumbers;
        this.properties = properties;
    }

    @Override
    public String next(InvoiceType type, LocalDate issueDate) {
        return runningNumbers.next(properties.numberPrefix(), properties.numberPadLength());
    }
}

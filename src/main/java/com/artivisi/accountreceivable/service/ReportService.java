package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.DebtorStatementResponse;
import com.artivisi.accountreceivable.dto.RecapResponse;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.DebtorRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReportService {

    private final DebtorRepository debtorRepository;
    private final InvoiceRepository invoiceRepository;

    public ReportService(DebtorRepository debtorRepository, InvoiceRepository invoiceRepository) {
        this.debtorRepository = debtorRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public DebtorStatementResponse statement(String debtorCode) {
        Debtor debtor = debtorRepository.findByCode(debtorCode)
                .orElseThrow(() -> new NotFoundException("Debtor not found: " + debtorCode));
        List<Invoice> invoices = invoiceRepository.findByDebtorCodeOrderByIssueDateAsc(debtorCode);

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        List<DebtorStatementResponse.Line> lines = new ArrayList<>();
        String currency = "IDR";
        for (Invoice i : invoices) {
            lines.add(DebtorStatementResponse.Line.from(i));
            totalAmount = totalAmount.add(i.getAmount());
            totalOutstanding = totalOutstanding.add(i.getOutstanding());
            currency = i.getCurrency();
        }
        return new DebtorStatementResponse(debtor.getCode(), debtor.getName(), currency,
                totalAmount, totalOutstanding, lines);
    }

    /**
     * Recap by invoice type. Grouped in SQL — this used to iterate {@code findAll()} over every
     * invoice ever issued to produce a handful of subtotals, which is one of the reads that
     * exhausted the heap on 2026-08-18. The report totals are summed from the per-type rows, of
     * which there are as many as there are invoice types.
     */
    @Transactional(readOnly = true)
    public RecapResponse recap() {
        List<RecapResponse.TypeLine> byType = invoiceRepository.recapByInvoiceType();

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (RecapResponse.TypeLine line : byType) {
            totalAmount = totalAmount.add(line.totalAmount());
            totalOutstanding = totalOutstanding.add(line.totalOutstanding());
        }
        return new RecapResponse(totalAmount, totalOutstanding, byType);
    }
}

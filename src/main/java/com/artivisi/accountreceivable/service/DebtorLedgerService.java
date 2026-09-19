package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.DebtorCollectionLine;
import com.artivisi.accountreceivable.dto.DebtorDetailResponse;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.DebtorRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the debtor detail page: header stats (outstanding, overdue, trailing-12-month collections),
 * a chronological statement ledger for the requested window, and the currently open invoices.
 *
 * <p>Collections are read with a projection query scoped to the one debtor. An earlier version read
 * every cash application with {@code findAll()} and filtered in memory; on 2026-08-18 that exhausted
 * the heap against tens of thousands of rows and took the application down, which made the bill mirror fail and
 * dropped a live receivable. Reporting reads here select what they need in SQL — never a whole table.
 */
@Service
public class DebtorLedgerService {

    private static final int TRAILING_MONTHS_FOR_COLLECTIONS = 12;

    private final DebtorRepository debtorRepository;
    private final InvoiceRepository invoiceRepository;
    private final CashApplicationRepository cashApplicationRepository;
    private final Clock clock;

    public DebtorLedgerService(DebtorRepository debtorRepository, InvoiceRepository invoiceRepository,
                               CashApplicationRepository cashApplicationRepository, Clock clock) {
        this.debtorRepository = debtorRepository;
        this.invoiceRepository = invoiceRepository;
        this.cashApplicationRepository = cashApplicationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DebtorDetailResponse detail(String debtorCode, int monthsBack) {
        Debtor debtor = debtorRepository.findByCode(debtorCode)
                .orElseThrow(() -> new NotFoundException("Debtor not found: " + debtorCode));
        List<Invoice> invoices = invoiceRepository.findByDebtorCodeOrderByIssueDateAsc(debtorCode);
        LocalDate today = LocalDate.now(clock);

        // Applied allocations belonging to this debtor, read once and reused below for both the
        // trailing-12-month collection stats and the ledger's credit rows. Two queries rather than
        // one: an allocation targets an invoice or an installment, and asking for both in a single
        // query costs the planner every index it could otherwise use (see the repository).
        List<DebtorCollectionLine> debtorLines = new ArrayList<>();
        debtorLines.addAll(cashApplicationRepository
                .findAppliedInvoiceLinesByDebtorCode(debtorCode, CashApplicationStatus.APPLIED));
        debtorLines.addAll(cashApplicationRepository
                .findAppliedInstallmentLinesByDebtorCode(debtorCode, CashApplicationStatus.APPLIED));

        BigDecimal outstandingTotal = BigDecimal.ZERO;
        long openInvoiceCount = 0;
        BigDecimal overdueTotal = BigDecimal.ZERO;
        long oldestOverdueDays = 0;
        List<RawEntry> rawEntries = new ArrayList<>();
        List<DebtorDetailResponse.OpenInvoice> openInvoices = new ArrayList<>();

        for (Invoice invoice : invoices) {
            rawEntries.add(new RawEntry(invoice.getIssueDate(), invoice.getInvoiceNumber(),
                    "Faktur — " + invoiceLabel(invoice), invoice.getAmount(), null));

            boolean open = invoice.getPaymentStatus() != PaymentStatus.WRITTEN_OFF
                    && invoice.getPaymentStatus() != PaymentStatus.CANCELLED
                    && invoice.getOutstanding().signum() > 0;
            if (open) {
                outstandingTotal = outstandingTotal.add(invoice.getOutstanding());
                openInvoiceCount++;
            }

            boolean overdue;
            long overdueDays;
            if (invoice.isInstallment() && invoice.getSchedule() != null) {
                boolean anyOverdue = false;
                long maxDays = 0;
                for (Installment installment : invoice.getSchedule().getInstallments()) {
                    if (installment.getOutstanding().signum() > 0 && installment.getDueDate().isBefore(today)) {
                        long days = ChronoUnit.DAYS.between(installment.getDueDate(), today);
                        overdueTotal = overdueTotal.add(installment.getOutstanding());
                        oldestOverdueDays = Math.max(oldestOverdueDays, days);
                        anyOverdue = true;
                        maxDays = Math.max(maxDays, days);
                    }
                }
                overdue = anyOverdue;
                overdueDays = maxDays;
            } else {
                overdue = invoice.getOutstanding().signum() > 0 && invoice.getDueDate().isBefore(today);
                overdueDays = overdue ? ChronoUnit.DAYS.between(invoice.getDueDate(), today) : 0;
                if (overdue) {
                    overdueTotal = overdueTotal.add(invoice.getOutstanding());
                    oldestOverdueDays = Math.max(oldestOverdueDays, overdueDays);
                }
            }

            if (open) {
                openInvoices.add(new DebtorDetailResponse.OpenInvoice(invoice.getId(), invoice.getInvoiceNumber(),
                        invoice.getInvoiceType().getCode(), invoice.getDueDate(), invoice.getOutstanding(),
                        invoice.getPaymentStatus(), overdue, overdueDays));
            }
        }

        LocalDate collectionsWindowStart = today.minusMonths(TRAILING_MONTHS_FOR_COLLECTIONS);
        BigDecimal totalPaid12Months = BigDecimal.ZERO;
        long dayToPaySum = 0;
        long dayToPayCount = 0;
        for (DebtorCollectionLine line : debtorLines) {
            LocalDate receivedDate = line.receivedAt().atZone(ZoneId.systemDefault()).toLocalDate();

            rawEntries.add(new RawEntry(receivedDate, line.gatewayPaymentReference(),
                    "Pembayaran" + (line.installmentSequence() != null
                            ? " cicilan " + line.installmentSequence() : ""),
                    null, line.allocatedAmount()));

            if (receivedDate.isBefore(collectionsWindowStart) || receivedDate.isAfter(today)) {
                continue;
            }
            totalPaid12Months = totalPaid12Months.add(line.allocatedAmount());
            dayToPaySum += ChronoUnit.DAYS.between(line.owningDueDate(), receivedDate);
            dayToPayCount++;
        }
        Double averageDaysToPay = dayToPayCount == 0 ? null : (double) dayToPaySum / dayToPayCount;

        LocalDate ledgerCutoff = monthsBack > 0 ? today.minusMonths(monthsBack) : null;
        List<RawEntry> windowed = rawEntries.stream()
                .filter(e -> ledgerCutoff == null || !e.date().isBefore(ledgerCutoff))
                .sorted(Comparator.comparing(RawEntry::date).thenComparing(RawEntry::reference))
                .toList();

        List<DebtorDetailResponse.LedgerEntry> ledger = new ArrayList<>();
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (RawEntry e : windowed) {
            if (e.debit() != null) {
                runningBalance = runningBalance.add(e.debit());
            }
            if (e.credit() != null) {
                runningBalance = runningBalance.subtract(e.credit());
            }
            ledger.add(new DebtorDetailResponse.LedgerEntry(e.date(), e.reference(), e.description(),
                    e.debit(), e.credit(), runningBalance));
        }

        return new DebtorDetailResponse(debtor.getCode(), debtor.getName(), debtor.getEmail(), debtor.getPhone(),
                debtor.getStatus(), outstandingTotal, openInvoiceCount, overdueTotal, oldestOverdueDays,
                totalPaid12Months, averageDaysToPay, monthsBack, ledger, openInvoices);
    }

    private static String invoiceLabel(Invoice invoice) {
        return invoice.getDescription() != null && !invoice.getDescription().isBlank()
                ? invoice.getDescription()
                : invoice.getInvoiceType().getName();
    }

    private record RawEntry(LocalDate date, String reference, String description,
                            BigDecimal debit, BigDecimal credit) {
    }
}

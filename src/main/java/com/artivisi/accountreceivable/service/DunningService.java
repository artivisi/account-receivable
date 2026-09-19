package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.DunningRunRequest;
import com.artivisi.accountreceivable.dto.DunningRunResponse;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.DunningReminder;
import com.artivisi.accountreceivable.entity.DunningRun;
import com.artivisi.accountreceivable.entity.DunningRunStatus;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.NotificationChannel;
import com.artivisi.accountreceivable.entity.ReminderStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.DunningRunRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.spi.DunningNotification;
import com.artivisi.accountreceivable.spi.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs reminder batches. Channel delivery is pluggable: a {@link NotificationSender} per channel is
 * provided by the deployment; requesting a channel with no sender fails loud. Each reminder's
 * delivery outcome is tracked (SENT/ERROR) — a failure never aborts the run.
 */
@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final InvoiceRepository invoiceRepository;
    private final DunningRunRepository dunningRunRepository;
    private final AuditService auditService;
    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final Clock clock;

    public DunningService(InvoiceRepository invoiceRepository,
                          DunningRunRepository dunningRunRepository,
                          AuditService auditService,
                          List<NotificationSender> notificationSenders,
                          Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.dunningRunRepository = dunningRunRepository;
        this.auditService = auditService;
        this.clock = clock;
        for (NotificationSender sender : notificationSenders) {
            if (senders.putIfAbsent(sender.channel(), sender) != null) {
                throw new IllegalStateException("Multiple NotificationSenders for channel " + sender.channel());
            }
        }
    }

    @Transactional
    public DunningRunResponse run(DunningRunRequest request) {
        NotificationSender sender = senders.get(request.channel());
        if (sender == null) {
            throw new InvalidRequestException("No NotificationSender registered for channel " + request.channel());
        }

        LocalDate today = LocalDate.now(clock);
        DunningRun run = new DunningRun();
        run.setRunDate(today);
        run.setChannel(request.channel());
        run.setMinDaysOverdue(request.minDaysOverdue());
        run.setStatus(DunningRunStatus.RUNNING);

        int sent = 0;
        int errors = 0;
        List<Invoice> overdue =
                invoiceRepository.findByOutstandingGreaterThanAndDueDateBefore(BigDecimal.ZERO, today);
        for (Invoice invoice : overdue) {
            long daysOverdue = ChronoUnit.DAYS.between(invoice.getDueDate(), today);
            if (daysOverdue < request.minDaysOverdue()) {
                continue;
            }
            DunningReminder reminder = new DunningReminder();
            reminder.setInvoice(invoice);
            reminder.setChannel(request.channel());

            Debtor debtor = invoice.getDebtor();
            String recipient = request.channel() == NotificationChannel.EMAIL
                    ? debtor.getEmail() : debtor.getPhone();
            reminder.setRecipient(recipient);

            if (recipient == null || recipient.isBlank()) {
                reminder.setStatus(ReminderStatus.ERROR);
                reminder.setError("No " + request.channel() + " recipient for debtor " + debtor.getCode());
                errors++;
            } else {
                try {
                    sender.send(new DunningNotification(request.channel(), recipient, debtor.getName(),
                            invoice.getInvoiceNumber(), invoice.getCurrency(), invoice.getOutstanding(),
                            invoice.getDueDate(), daysOverdue));
                    reminder.setStatus(ReminderStatus.SENT);
                    reminder.setSentAt(Instant.now(clock));
                    sent++;
                    auditService.record("DUNNING_REMINDER_SENT", "Invoice", invoice.getId(),
                            request.channel() + " to " + recipient);
                } catch (Exception e) {
                    reminder.setStatus(ReminderStatus.ERROR);
                    reminder.setError(e.getMessage());
                    errors++;
                    log.warn("Dunning reminder failed for invoice {}: {}",
                            invoice.getInvoiceNumber(), e.getMessage());
                }
            }
            run.addReminder(reminder);
        }

        run.setTotalReminders(run.getReminders().size());
        run.setSentCount(sent);
        run.setErrorCount(errors);
        run.setStatus(DunningRunStatus.DONE);
        DunningRun saved = dunningRunRepository.save(run);
        auditService.record("DUNNING_RUN", "DunningRun", saved.getId(),
                request.channel() + " sent=" + sent + " errors=" + errors);
        return DunningRunResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DunningRunResponse> list() {
        return dunningRunRepository.findAll().stream().map(DunningRunResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DunningRunResponse get(String id) {
        return DunningRunResponse.from(dunningRunRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Dunning run not found: " + id)));
    }
}

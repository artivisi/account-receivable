package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.DunningReminder;
import com.artivisi.accountreceivable.entity.DunningRun;
import com.artivisi.accountreceivable.entity.DunningRunStatus;
import com.artivisi.accountreceivable.entity.NotificationChannel;
import com.artivisi.accountreceivable.entity.ReminderStatus;

import java.time.LocalDate;
import java.util.List;

public record DunningRunResponse(
        String id,
        LocalDate runDate,
        NotificationChannel channel,
        int minDaysOverdue,
        DunningRunStatus status,
        int totalReminders,
        int sentCount,
        int errorCount,
        List<ReminderView> reminders
) {

    public record ReminderView(
            String invoiceNumber,
            String recipient,
            ReminderStatus status,
            String error
    ) {
        static ReminderView from(DunningReminder r) {
            return new ReminderView(r.getInvoice().getInvoiceNumber(), r.getRecipient(),
                    r.getStatus(), r.getError());
        }
    }

    public static DunningRunResponse from(DunningRun run) {
        return new DunningRunResponse(
                run.getId(), run.getRunDate(), run.getChannel(), run.getMinDaysOverdue(),
                run.getStatus(), run.getTotalReminders(), run.getSentCount(), run.getErrorCount(),
                run.getReminders().stream().map(ReminderView::from).toList());
    }
}

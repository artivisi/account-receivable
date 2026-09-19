package com.artivisi.accountreceivable.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A reminder batch: the selection criteria and the reminders it produced. */
@Getter
@Setter
@Entity
@Table(name = "dunning_run")
public class DunningRun extends BaseEntity {

    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    private int minDaysOverdue;

    @Enumerated(EnumType.STRING)
    private DunningRunStatus status;

    private int totalReminders;

    private int sentCount;

    private int errorCount;

    @OneToMany(mappedBy = "dunningRun", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DunningReminder> reminders = new ArrayList<>();

    public void addReminder(DunningReminder reminder) {
        reminder.setDunningRun(this);
        reminders.add(reminder);
    }
}

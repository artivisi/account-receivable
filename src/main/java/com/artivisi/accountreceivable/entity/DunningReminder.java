package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One reminder for one overdue invoice within a {@link DunningRun}. */
@Getter
@Setter
@Entity
@Table(name = "dunning_reminder")
public class DunningReminder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_dunning_run")
    private DunningRun dunningRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    private String recipient;

    @Enumerated(EnumType.STRING)
    private ReminderStatus status;

    private Instant sentAt;

    private String error;
}

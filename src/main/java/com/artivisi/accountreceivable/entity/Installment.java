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

import java.math.BigDecimal;
import java.time.LocalDate;

/** One ordered installment within a {@link PaymentSchedule}. Collected by its own CLOSED charge. */
@Getter
@Setter
@Entity
@Table(name = "installment")
public class Installment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_schedule")
    private PaymentSchedule schedule;

    private int sequence;

    private LocalDate dueDate;

    private BigDecimal amount;

    private BigDecimal outstanding;

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus;
}

package com.artivisi.accountreceivable.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Installment container for an invoice. Σ installment amounts = invoice amount. */
@Getter
@Setter
@Entity
@Table(name = "payment_schedule")
public class PaymentSchedule extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    private int installmentCount;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence asc")
    private List<Installment> installments = new ArrayList<>();

    public void addInstallment(Installment installment) {
        installment.setSchedule(this);
        installments.add(installment);
    }

    /** Installments in collection order: by due date, then sequence. */
    public List<Installment> ordered() {
        return installments.stream()
                .sorted(Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getSequence))
                .toList();
    }

    /** The plan's own deadline: when its last installment falls due. */
    public LocalDate lastDueDate() {
        return ordered().getLast().getDueDate();
    }

    /**
     * What the plan's single VA must answer on {@code today}: every unpaid installment that is
     * overdue, plus the next unpaid one that is not. An installment that passes its date unpaid
     * therefore rides with the next one from the following day — campus finance's rule (2026-11-03):
     * arrears on leg 1 make leg 2 worth 1+2 as soon as leg 1 is late, not on leg 2's own date.
     *
     * <p>A due date is inclusive, as {@code CollectionService.expiryFor} has it: a leg due today is
     * not yet overdue, so on that day the VA still answers it alone.
     *
     * <p>This is the rule the whole instalment design rests on. The VA number space has no room for
     * an installment index, so one CLOSED charge is repriced through the plan, and this figure is
     * what it is repriced to.
     */
    public BigDecimal amountDueOn(LocalDate today) {
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        boolean nextTaken = false;
        for (Installment leg : ordered()) {
            if (leg.getOutstanding().signum() == 0) {
                continue;
            }
            boolean overdue = leg.getDueDate().isBefore(today);
            if (overdue || !nextTaken) {
                due = due.add(leg.getOutstanding());
            }
            if (!overdue) {
                nextTaken = true;
            }
        }
        return due;
    }
}

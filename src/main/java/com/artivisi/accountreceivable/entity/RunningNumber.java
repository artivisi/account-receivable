package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-prefix monotonic counter for document numbering (e.g. invoice numbers).
 * Allocation is row-locked in {@code RunningNumberService}.
 */
@Getter
@Setter
@Entity
@Table(name = "running_number")
public class RunningNumber extends BaseEntity {

    private String prefix;

    private long lastNumber;
}

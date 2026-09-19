package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The party that owes (customer / payer). Master data only — no academic fields in the engine.
 */
@Getter
@Setter
@Entity
@Table(name = "debtor")
public class Debtor extends BaseEntity {

    private String code;

    private String name;

    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    private DebtorStatus status;
}

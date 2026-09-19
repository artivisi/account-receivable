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

/** Outbox row: cancel a gateway charge, retried until DONE or terminal FAILED. */
@Getter
@Setter
@Entity
@Table(name = "charge_cancellation")
public class ChargeCancellation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    private String gatewayChargeId;

    @Enumerated(EnumType.STRING)
    private CancellationStatus status;

    private int attempts;

    private int maxAttempts;

    private Instant nextAttemptAt;

    private Integer lastResponseCode;

    private String lastError;
}

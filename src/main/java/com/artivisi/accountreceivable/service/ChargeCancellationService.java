package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.client.GatewayConsumerClient;
import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.entity.CancellationStatus;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeCancellation;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.repository.ChargeCancellationRepository;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Outbound outbox for cancelling gateway charges on write-off. {@link #enqueueForWriteOff} runs in
 * the write-off transaction; {@link #cancel} posts each cancellation in its own transaction with
 * backoff, marking the local charge CANCELLED on success. Terminal FAILED is surfaced, never dropped.
 */
@Service
public class ChargeCancellationService {

    private static final Logger log = LoggerFactory.getLogger(ChargeCancellationService.class);
    private static final int BATCH_SIZE = 50;
    private static final int ERROR_MAX = 500;

    private final ChargeRepository chargeRepository;
    private final ChargeCancellationRepository cancellationRepository;
    private final GatewayConsumerClient gatewayClient;
    private final ArGatewayProperties properties;
    private final Clock clock;

    public ChargeCancellationService(ChargeRepository chargeRepository,
                                     ChargeCancellationRepository cancellationRepository,
                                     GatewayConsumerClient gatewayClient,
                                     ArGatewayProperties properties,
                                     Clock clock) {
        this.chargeRepository = chargeRepository;
        this.cancellationRepository = cancellationRepository;
        this.gatewayClient = gatewayClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Enqueue cancellation of any still-collectible charges for a written-off invoice.
     *
     * <p>Charges are resolved through the target foreign key, not through {@code consumerReference}.
     * The two agree for charges this application opened — {@code CollectionService} passes the
     * target id as the reference — but a charge row mirrored in from another system carries that
     * system's own key instead, and then a reference lookup matches nothing, {@code ifPresent}
     * does nothing, and the write-off completes having cancelled nothing: the receivable is
     * forgiven here while its VA keeps collecting at the gateway. That failure is silent, which is
     * what makes it expensive — one production deployment forgave 71 invoices whose charges stayed
     * live, and the gap only surfaced when the books were reconciled against the gateway months
     * later. The FK is what actually defines the relationship, and it is what
     * {@code CollectionService.amendDueDate} already uses to reach the same charges.
     *
     * <p>Invoice- and installment-targeted charges are disjoint sets ({@code chk_charge_one_target}
     * allows exactly one target per charge), so scanning both cannot enqueue a charge twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueForWriteOff(Invoice invoice) {
        chargeRepository.findByInvoiceId(invoice.getId()).forEach(this::enqueueIfOpen);
        if (invoice.isInstallment()) {
            for (Installment installment : invoice.getSchedule().getInstallments()) {
                chargeRepository.findByInstallmentId(installment.getId()).forEach(this::enqueueIfOpen);
            }
        }
    }

    private void enqueueIfOpen(Charge charge) {
        if (charge.getStatus() != ChargeStatus.ACTIVE && charge.getStatus() != ChargeStatus.PARTIALLY_PAID) {
            return;
        }
        ChargeCancellation cancellation = new ChargeCancellation();
        cancellation.setCharge(charge);
        cancellation.setGatewayChargeId(charge.getGatewayChargeId());
        cancellation.setStatus(CancellationStatus.PENDING);
        cancellation.setAttempts(0);
        cancellation.setMaxAttempts(properties.maxAttempts());
        cancellation.setNextAttemptAt(Instant.now(clock));
        cancellationRepository.save(cancellation);
    }

    @Transactional(readOnly = true)
    public List<ChargeCancellation> findDue() {
        return cancellationRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                CancellationStatus.PENDING, Instant.now(clock), Limit.of(BATCH_SIZE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(String id) {
        ChargeCancellation row = cancellationRepository.findById(id).orElse(null);
        if (row == null || row.getStatus() != CancellationStatus.PENDING) {
            return;
        }
        try {
            gatewayClient.cancelCharge(row.getGatewayChargeId());
            row.setStatus(CancellationStatus.DONE);
            row.getCharge().cancel(java.time.Instant.now(clock));
            row.setLastError(null);
            row.setLastResponseCode(null);
        } catch (RestClientResponseException e) {
            recordFailure(row, e.getStatusCode().value(), e.getMessage());
        } catch (Exception e) {
            recordFailure(row, null, e.getMessage());
        }
    }

    private void recordFailure(ChargeCancellation row, Integer responseCode, String error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastResponseCode(responseCode);
        row.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)));
        if (attempts >= row.getMaxAttempts()) {
            row.setStatus(CancellationStatus.FAILED);
            log.error("Charge cancellation {} FAILED after {} attempts: {}", row.getId(), attempts, error);
        } else {
            long backoff = properties.backoffBaseSeconds() * (1L << (attempts - 1));
            row.setNextAttemptAt(Instant.now(clock).plusSeconds(backoff));
            log.warn("Charge cancellation {} attempt {} failed, retrying in {}s", row.getId(), attempts, backoff);
        }
    }
}

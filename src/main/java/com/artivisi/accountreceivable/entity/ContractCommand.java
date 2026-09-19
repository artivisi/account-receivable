package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A command already processed, keyed by its {@code idempotencyKey}. A repeat with the same key gets
 * the stored result republished, verbatim — same eventId — so the sender sees the first outcome and
 * a consumer that already applied it ignores the copy. Rejections are stored too: repeating a
 * rejected command repeats the rejection, it does not retry it.
 */
@Getter
@Setter
@Entity
@Table(name = "contract_command")
public class ContractCommand extends BaseEntity {

    private String idempotencyKey;
    private String commandType;
    private String messageKey;
    private String resultTopic;
    private String resultPayload;
    private Instant receivedAt;
}

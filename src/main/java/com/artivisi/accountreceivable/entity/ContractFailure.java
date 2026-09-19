package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A command that could not be processed for a reason that is not the sender's — an exception past
 * validation. The offset moves on so one message cannot stall the partition; this row is how it is
 * found and replayed. A row here is an incident, not a backlog.
 */
@Getter
@Setter
@Entity
@Table(name = "contract_failure")
public class ContractFailure extends BaseEntity {

    private String topic;
    private String messageKey;
    private String payload;
    private String error;
    private boolean resolved;
}

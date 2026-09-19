package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.ContractOutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ContractEventOutboxRepository extends JpaRepository<ContractEventOutbox, String> {

    List<ContractEventOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            ContractOutboxStatus status, Instant now, Limit limit);

    List<ContractEventOutbox> findByMessageKeyOrderByCreatedAtAsc(String messageKey);
}

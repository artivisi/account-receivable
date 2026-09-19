package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.NotificationOutbox;
import com.artivisi.accountreceivable.entity.NotificationOutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, String> {

    List<NotificationOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            NotificationOutboxStatus status, Instant cutoff, Limit limit);
}

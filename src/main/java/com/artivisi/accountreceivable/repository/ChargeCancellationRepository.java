package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.ChargeCancellation;
import com.artivisi.accountreceivable.entity.CancellationStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ChargeCancellationRepository extends JpaRepository<ChargeCancellation, String> {

    List<ChargeCancellation> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            CancellationStatus status, Instant cutoff, Limit limit);
}

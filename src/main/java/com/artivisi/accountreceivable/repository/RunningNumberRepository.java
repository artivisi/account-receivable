package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.RunningNumber;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface RunningNumberRepository extends JpaRepository<RunningNumber, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RunningNumber> findByPrefix(String prefix);
}

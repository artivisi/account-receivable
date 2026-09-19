package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.ContractCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContractCommandRepository extends JpaRepository<ContractCommand, String> {
    Optional<ContractCommand> findByIdempotencyKey(String idempotencyKey);
}

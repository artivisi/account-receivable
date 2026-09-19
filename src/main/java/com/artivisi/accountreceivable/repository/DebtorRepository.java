package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.Debtor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebtorRepository extends JpaRepository<Debtor, String> {

    Optional<Debtor> findByCode(String code);

    boolean existsByCode(String code);

    Page<Debtor> findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(String code, String name, Pageable pageable);
}

package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.Installment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepository extends JpaRepository<Installment, String> {
}

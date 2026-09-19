package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceTypeVaCodeRepository extends JpaRepository<InvoiceTypeVaCode, String> {

    Optional<InvoiceTypeVaCode> findByInvoiceTypeId(String invoiceTypeId);

    Optional<InvoiceTypeVaCode> findByInvoiceTypeCode(String invoiceTypeCode);

    boolean existsByVaCodeAndInvoiceTypeIdNot(String vaCode, String invoiceTypeId);
}

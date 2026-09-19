package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditNoteRepository extends JpaRepository<CreditNote, String> {

    List<CreditNote> findByInvoiceId(String invoiceId);
}

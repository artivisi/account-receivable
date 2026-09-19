package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvoiceTypeRepository extends JpaRepository<InvoiceType, String> {

    Optional<InvoiceType> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Paginated type search. {@code activeOnly=true} restricts to selectable types (the invoice-form
     * picker); {@code false} lists all (the API). {@code q} (already lower-cased and {@code %}-wrapped,
     * or null) matches code or name.
     */
    @Query(value = """
            select t from InvoiceType t
            where (:activeOnly = false or t.active = true)
              and (:q is null or lower(t.code) like :q or lower(t.name) like :q)
            """,
            countQuery = """
            select count(t) from InvoiceType t
            where (:activeOnly = false or t.active = true)
              and (:q is null or lower(t.code) like :q or lower(t.name) like :q)
            """)
    Page<InvoiceType> search(@Param("activeOnly") boolean activeOnly, @Param("q") String q, Pageable pageable);
}

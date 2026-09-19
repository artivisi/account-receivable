package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.BulkUploadBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkUploadBatchRepository extends JpaRepository<BulkUploadBatch, String> {
}

package com.artivisi.accountreceivable.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A bulk receivable upload: counts + the per-row errors encountered. */
@Getter
@Setter
@Entity
@Table(name = "bulk_upload_batch")
public class BulkUploadBatch extends BaseEntity {

    private String filename;

    private Instant uploadedAt;

    private int totalRows;

    private int successCount;

    private int errorCount;

    @Enumerated(EnumType.STRING)
    private BulkUploadStatus status;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BulkUploadError> errors = new ArrayList<>();

    public void addError(BulkUploadError error) {
        error.setBatch(this);
        errors.add(error);
    }
}

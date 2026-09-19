package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One rejected row in a {@link BulkUploadBatch}, with its source line number. */
@Getter
@Setter
@Entity
@Table(name = "bulk_upload_error")
public class BulkUploadError extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_batch")
    private BulkUploadBatch batch;

    private int lineNo;

    private String message;
}

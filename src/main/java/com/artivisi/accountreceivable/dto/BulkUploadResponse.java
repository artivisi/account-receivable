package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.BulkUploadBatch;
import com.artivisi.accountreceivable.entity.BulkUploadStatus;

import java.util.List;

public record BulkUploadResponse(
        String batchId,
        String filename,
        int totalRows,
        int successCount,
        int errorCount,
        BulkUploadStatus status,
        List<RowError> errors
) {

    public record RowError(int lineNo, String message) {
    }

    public static BulkUploadResponse from(BulkUploadBatch batch) {
        return new BulkUploadResponse(
                batch.getId(), batch.getFilename(), batch.getTotalRows(), batch.getSuccessCount(),
                batch.getErrorCount(), batch.getStatus(),
                batch.getErrors().stream()
                        .map(e -> new RowError(e.getLineNo(), e.getMessage())).toList());
    }
}

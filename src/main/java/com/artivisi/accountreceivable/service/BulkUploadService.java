package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.BulkUploadResponse;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.entity.BulkUploadBatch;
import com.artivisi.accountreceivable.entity.BulkUploadError;
import com.artivisi.accountreceivable.entity.BulkUploadStatus;
import com.artivisi.accountreceivable.repository.BulkUploadBatchRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Bulk receivable upload: one single-line invoice per CSV row. Each row is issued in its own
 * transaction (via {@link InvoiceService}); a bad row is recorded with its line number and skipped
 * — others still post. Errors are reported explicitly (fail loud), never silently dropped.
 *
 * <p>Columns: {@code debtorCode,invoiceTypeCode,issueDate,dueDate,amount,description} with a header row.
 */
@Service
public class BulkUploadService {

    private static final int COLUMNS = 6;

    private final InvoiceService invoiceService;
    private final BulkUploadBatchRepository batchRepository;
    private final Clock clock;

    public BulkUploadService(InvoiceService invoiceService,
                             BulkUploadBatchRepository batchRepository,
                             Clock clock) {
        this.invoiceService = invoiceService;
        this.batchRepository = batchRepository;
        this.clock = clock;
    }

    /** Not transactional: each row issues in its own tx so one failure cannot roll back the others. */
    public BulkUploadResponse upload(String filename, byte[] content) {
        BulkUploadBatch batch = new BulkUploadBatch();
        batch.setFilename(filename);
        batch.setUploadedAt(Instant.now(clock));

        String[] lines = new String(content, StandardCharsets.UTF_8).split("\r?\n");
        int totalRows = 0;
        int success = 0;
        boolean headerSkipped = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }
            int lineNo = i + 1;
            totalRows++;
            try {
                issueRow(line);
                success++;
            } catch (Exception e) {
                BulkUploadError error = new BulkUploadError();
                error.setLineNo(lineNo);
                error.setMessage(message(e));
                batch.addError(error);
            }
        }

        batch.setTotalRows(totalRows);
        batch.setSuccessCount(success);
        batch.setErrorCount(batch.getErrors().size());
        batch.setStatus(batch.getErrors().isEmpty()
                ? BulkUploadStatus.COMPLETED : BulkUploadStatus.COMPLETED_WITH_ERRORS);
        return BulkUploadResponse.from(batchRepository.save(batch));
    }

    private void issueRow(String line) {
        List<String> f = CsvParser.parseLine(line);
        if (f.size() < COLUMNS) {
            throw new IllegalArgumentException(
                    "expected " + COLUMNS + " columns, got " + f.size());
        }
        String debtorCode = required(f.get(0), "debtorCode");
        String invoiceTypeCode = required(f.get(1), "invoiceTypeCode");
        LocalDate issueDate = LocalDate.parse(required(f.get(2), "issueDate"));
        LocalDate dueDate = LocalDate.parse(required(f.get(3), "dueDate"));
        BigDecimal amount = new BigDecimal(required(f.get(4), "amount"));
        String description = required(f.get(5), "description");

        IssueInvoiceRequest request = new IssueInvoiceRequest(
                debtorCode, invoiceTypeCode, issueDate, dueDate, description,
                List.of(new IssueInvoiceRequest.LineRequest(description, BigDecimal.ONE, amount)),
                null);
        invoiceService.issue(request);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String message(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.substring(0, Math.min(msg.length(), 500));
    }
}

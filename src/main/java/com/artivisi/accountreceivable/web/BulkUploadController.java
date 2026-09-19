package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.BulkUploadResponse;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.service.BulkUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class BulkUploadController {

    private final BulkUploadService service;

    public BulkUploadController(BulkUploadService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/invoices/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkUploadResponse upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidRequestException("Uploaded file is empty");
        }
        String filename = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        try {
            return service.upload(filename, file.getBytes());
        } catch (IOException e) {
            throw new InvalidRequestException("Failed to read uploaded file: " + e.getMessage());
        }
    }
}

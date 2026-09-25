package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.service.CollectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit-notes")
public class CreditNoteController {

    private final CollectionService service;

    public CreditNoteController(CollectionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreditNoteResponse> issue(@Valid @RequestBody CreditNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueCreditNote(request));
    }
}

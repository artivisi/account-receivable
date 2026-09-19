package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DunningRunRequest;
import com.artivisi.accountreceivable.dto.DunningRunResponse;
import com.artivisi.accountreceivable.service.DunningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dunning/runs")
public class DunningController {

    private final DunningService service;

    public DunningController(DunningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DunningRunResponse> run(@Valid @RequestBody DunningRunRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.run(request));
    }

    @GetMapping("/{id}")
    public DunningRunResponse get(@PathVariable String id) {
        return service.get(id);
    }
}

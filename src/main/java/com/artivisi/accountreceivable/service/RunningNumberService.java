package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.RunningNumber;
import com.artivisi.accountreceivable.repository.RunningNumberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates monotonic document numbers per prefix. The counter row is locked
 * ({@code PESSIMISTIC_WRITE}) for the duration of the caller's transaction, so concurrent issuers
 * cannot collide. The counter is lazily created on first use for a prefix.
 */
@Service
public class RunningNumberService {

    private final RunningNumberRepository repository;

    public RunningNumberService(RunningNumberRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix, int padLength) {
        return prefix + String.format("%0" + padLength + "d", nextValue(prefix));
    }

    /**
     * The counter alone, for numbering schemes where the sequence is not simply appended to its own
     * key. A dated scheme keys the counter on the day but renders the day, a type code and the
     * sequence in that order, so it needs the value rather than a formatted string.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextValue(String counterKey) {
        RunningNumber counter = repository.findByPrefix(counterKey).orElseGet(() -> {
            RunningNumber created = new RunningNumber();
            created.setPrefix(counterKey);
            created.setLastNumber(0L);
            return repository.save(created);
        });
        long value = counter.getLastNumber() + 1;
        counter.setLastNumber(value);
        return value;
    }
}

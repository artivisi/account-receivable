package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.service.numbering.DatedTypeCodeInvoiceNumberStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatedTypeCodeInvoiceNumberStrategyTest {

    /** Records which counter each call asked for — the property that decides interleaving. */
    private static class RecordingCounters extends RunningNumberService {
        final List<String> keys = new ArrayList<>();
        long value;

        RecordingCounters() {
            super(null);
        }

        @Override
        public long nextValue(String counterKey) {
            keys.add(counterKey);
            return ++value;
        }
    }

    private static ArInvoiceProperties props() {
        return new ArInvoiceProperties(null, "CN", 6, ArInvoiceProperties.Strategy.DATED_TYPE_CODE);
    }

    /** Only the type-digit width matters here; it is what the VA number pads the same code to. */
    private static ArGatewayProperties gateway() {
        return new ArGatewayProperties("http://gw", "id", "secret", "escrow", 3, 0, 3600000,
                "", 12, 2);
    }

    private static InvoiceType type(String code) {
        InvoiceType t = mock(InvoiceType.class);
        when(t.getCode()).thenReturn(code);
        return t;
    }

    private static InvoiceTypeVaCodeRepository codes(String typeCode, String vaCode) {
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        InvoiceTypeVaCode mapping = mock(InvoiceTypeVaCode.class);
        when(mapping.getVaCode()).thenReturn(vaCode);
        when(repo.findByInvoiceTypeCode(typeCode)).thenReturn(Optional.of(mapping));
        return repo;
    }

    @Test
    void rendersTheDateThenTheTypeCodeThenTheSequence() {
        RecordingCounters counters = new RecordingCounters();
        var strategy = new DatedTypeCodeInvoiceNumberStrategy(counters, codes("ukt", "40"), props(), gateway());

        String number = strategy.next(type("ukt"), LocalDate.of(2026, 8, 27));

        assertThat(number).isEqualTo("2026082740000001");
    }

    @Test
    void differentTypesOnOneDayShareThatDaysSequence() {
        // The shape that cannot be produced by appending a number to its own key, and the reason the
        // counter is keyed on the date alone: a UKT bill and a hostel bill issued the same morning
        // take 1 and 2 of that day's run, not 1 and 1 of two separate runs.
        RecordingCounters counters = new RecordingCounters();
        InvoiceTypeVaCodeRepository repo = mock(InvoiceTypeVaCodeRepository.class);
        InvoiceTypeVaCode ukt = mock(InvoiceTypeVaCode.class);
        when(ukt.getVaCode()).thenReturn("40");
        InvoiceTypeVaCode asrama = mock(InvoiceTypeVaCode.class);
        when(asrama.getVaCode()).thenReturn("03");
        when(repo.findByInvoiceTypeCode("ukt")).thenReturn(Optional.of(ukt));
        when(repo.findByInvoiceTypeCode("asrama")).thenReturn(Optional.of(asrama));
        var strategy = new DatedTypeCodeInvoiceNumberStrategy(counters, repo, props(), gateway());
        LocalDate day = LocalDate.of(2026, 8, 27);

        assertThat(strategy.next(type("ukt"), day)).isEqualTo("2026082740000001");
        assertThat(strategy.next(type("asrama"), day)).isEqualTo("2026082703000002");
        assertThat(strategy.next(type("ukt"), day)).isEqualTo("2026082740000003");

        assertThat(counters.keys).containsExactly("20260827", "20260827", "20260827");
    }

    @Test
    void anotherDayStartsItsOwnRun() {
        RecordingCounters counters = new RecordingCounters();
        var strategy = new DatedTypeCodeInvoiceNumberStrategy(counters, codes("ukt", "40"), props(), gateway());

        strategy.next(type("ukt"), LocalDate.of(2026, 8, 27));
        strategy.next(type("ukt"), LocalDate.of(2026, 8, 28));

        assertThat(counters.keys).containsExactly("20260827", "20260828");
    }

    @Test
    void aSingleDigitCodeIsPaddedSoThePositionsNeverShift() {
        // The stored code may be one or two digits, but every consumer downstream reads the type by
        // position — a fifteen-character number would break all of them silently.
        RecordingCounters counters = new RecordingCounters();
        var strategy = new DatedTypeCodeInvoiceNumberStrategy(
                counters, codes("wisuda", "3"), props(), gateway());

        String number = strategy.next(type("wisuda"), LocalDate.of(2026, 8, 27));

        assertThat(number).isEqualTo("2026082703000001").hasSize(16);
    }

    @Test
    void aTypeWithNoVaCodeIsRefused() {
        // Substituting a placeholder would mint a number the institution's other systems cannot
        // parse, and would do it silently.
        RecordingCounters counters = new RecordingCounters();
        InvoiceTypeVaCodeRepository empty = mock(InvoiceTypeVaCodeRepository.class);
        when(empty.findByInvoiceTypeCode("wisuda")).thenReturn(Optional.empty());
        var strategy = new DatedTypeCodeInvoiceNumberStrategy(counters, empty, props(), gateway());

        assertThatThrownBy(() -> strategy.next(type("wisuda"), LocalDate.of(2026, 8, 27)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("wisuda")
                .hasMessageContaining("no VA code");
        assertThat(counters.keys).isEmpty();
    }
}

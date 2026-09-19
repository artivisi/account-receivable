package com.artivisi.accountreceivable.web;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyFormatTest {

    private final MoneyFormat money = new MoneyFormat();

    @Test
    void rupiahFormatsWithPrefixAndPeriodGrouping() {
        assertEquals("Rp 1.234.567.890", money.rupiah(new BigDecimal("1234567890.00")));
    }

    @Test
    void amountFormatsWithPeriodGrouping() {
        assertEquals("1.500.000", money.amount(new BigDecimal("1500000.00")));
    }

    @Test
    void amountFormatsIntegerInput() {
        assertEquals("750.000", money.amount(new BigDecimal("750000")));
    }

    @Test
    void amountFormatsZero() {
        assertEquals("0", money.amount(BigDecimal.ZERO));
    }

    @Test
    void rupiahNullReturnsEmpty() {
        assertEquals("", money.rupiah(null));
    }

    @Test
    void amountNullReturnsEmpty() {
        assertEquals("", money.amount(null));
    }
}

package com.artivisi.accountreceivable.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Indonesian-locale money formatting for Thymeleaf templates, reachable as ${@money...}.
 * Whole-Rupiah convention: thousands grouped with '.', no decimal places (IDR has no sub-unit in use).
 * A fresh NumberFormat is created per call — NumberFormat is not thread-safe and this bean is a
 * singleton shared across request threads.
 */
@Component("money")
public class MoneyFormat {

    private static final Locale ID = Locale.of("id", "ID");

    /** "Rp 1.234.567.890" — for headline / stat-card / total figures. Null -> "". */
    public String rupiah(BigDecimal value) {
        return value == null ? "" : "Rp " + amount(value);
    }

    /** "1.234.567.890" — for table-cell amounts (no prefix). Null -> "". */
    public String amount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        NumberFormat f = NumberFormat.getInstance(ID);
        f.setMaximumFractionDigits(0);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value);
    }
}

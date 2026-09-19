package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Invoice-numbering scheme. Required; missing config fails startup (fail loud, no default prefix).
 *
 * <p>{@code numberStrategy} picks the format. {@code SEQUENTIAL} is the product default and uses
 * {@code numberPrefix}; {@code DATED_TYPE_CODE} renders the issue date, the invoice type's VA code
 * and the day's sequence, and ignores the prefix — so the prefix is required only for the strategy
 * that reads it, rather than demanded of every deployment for the sake of one.
 */
@Validated
@ConfigurationProperties(prefix = "ar.invoice")
public record ArInvoiceProperties(
        String numberPrefix,
        @NotBlank String creditNotePrefix,
        @Min(1) int numberPadLength,
        Strategy numberStrategy
) {

    public enum Strategy {
        /** Configured prefix plus one ever-increasing sequence: {@code INV000123}. */
        SEQUENTIAL,
        /** {@code yyyyMMdd} + invoice type VA code + the day's sequence: {@code 2026082740000004}. */
        DATED_TYPE_CODE
    }

    public ArInvoiceProperties {
        if (numberStrategy == null) {
            throw new IllegalArgumentException(
                    "ar.invoice.number-strategy is required — set SEQUENTIAL or DATED_TYPE_CODE."
                            + " There is no default: which shape a deployment's bill numbers take is"
                            + " not something to be guessed on its behalf");
        }
        if (numberStrategy == Strategy.SEQUENTIAL && (numberPrefix == null || numberPrefix.isBlank())) {
            throw new IllegalArgumentException(
                    "ar.invoice.number-prefix is required when number-strategy is SEQUENTIAL");
        }
    }
}

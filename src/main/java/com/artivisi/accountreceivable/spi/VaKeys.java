package com.artivisi.accountreceivable.spi;

/**
 * {@code payerKey}: a stable identifier for the payer, the same on every one of their charges,
 * because payers keep the number in their banking app. {@code slotKey}: a short numeric key
 * separating one payer's concurrent bills.
 */
public record VaKeys(String payerKey, String slotKey) {
}

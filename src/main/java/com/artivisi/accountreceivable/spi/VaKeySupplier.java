package com.artivisi.accountreceivable.spi;

/**
 * Supplies the two keys the gateway allocates a charge's VA number from.
 *
 * <p>This used to compose the number itself, which needed a copy of the bank's layout — prefix,
 * total length, segment widths, padding — in AR's configuration. That copy is what made AR
 * single-bank, and it was right only until the layout changed or a second escrow existed. The
 * gateway holds the layout and composes the number now; what stays here is what only AR knows:
 * which payer a bill belongs to, and which of that payer's concurrent bills it is.
 *
 * <p>Both keys must be bank-agnostic. The same pair is sent for every escrow a charge targets, and
 * each escrow composes its own number from it.
 *
 * <p>The engine ships {@code InvoiceTypeVaKeySupplier} as the default (active when no other bean of
 * this type is present). Override with a {@code @Bean} where a deployment identifies payers or
 * separates their bills differently.
 */
public interface VaKeySupplier {

    VaKeys keys(VaAllocationContext ctx);
}

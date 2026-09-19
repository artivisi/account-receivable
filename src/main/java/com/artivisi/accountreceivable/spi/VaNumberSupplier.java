package com.artivisi.accountreceivable.spi;

/**
 * Allocates a virtual-account number within an escrow's number space when opening a charge.
 *
 * <p>The engine ships {@code EncodedVaNumberSupplier} as a config-driven default (active when no
 * other bean of this type is present). Override with a {@code @Bean} for exotic schemes.
 */
public interface VaNumberSupplier {

    String allocate(VaAllocationContext ctx);
}

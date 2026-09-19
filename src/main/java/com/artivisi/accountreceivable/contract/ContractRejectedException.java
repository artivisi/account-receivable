package com.artivisi.accountreceivable.contract;

/** A command AR refuses on the contract's rules; becomes an {@code invoice.rejected} event. */
public class ContractRejectedException extends RuntimeException {

    private final String code;

    public ContractRejectedException(String code, String reason) {
        super(reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

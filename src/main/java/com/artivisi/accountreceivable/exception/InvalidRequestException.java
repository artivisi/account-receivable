package com.artivisi.accountreceivable.exception;

/**
 * A request AR refuses on its own rules. {@code code} is the contract rejection code
 * ({@code invoice.rejected}) when the refusal has one; null means the caller supplies the default
 * for the command being processed.
 */
public class InvalidRequestException extends RuntimeException {

    private final String code;

    public InvalidRequestException(String message) {
        this(null, message);
    }

    public InvalidRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

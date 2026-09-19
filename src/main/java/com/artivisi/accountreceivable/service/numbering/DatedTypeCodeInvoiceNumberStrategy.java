package com.artivisi.accountreceivable.service.numbering;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.service.RunningNumberService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * {@code yyyyMMdd} + the type's code + a zero-padded sequence, e.g. {@code 2026082740000004}.
 *
 * <p>For a deployment that already prints this shape on its bills and whose students, staff and
 * campus applications all quote it. Two properties of it are easy to get wrong:
 *
 * <ul>
 *   <li><b>The sequence is per day, not per day and type.</b> Bills of different types interleave in
 *       one day's run — …40000001, …03000002, …40000003 — so the counter is keyed on the date alone
 *       and the type code is rendered between the date and the sequence. That is why this cannot be
 *       expressed as a prefix with a number appended.</li>
 *   <li><b>The type code is the one already on the invoice type.</b> It is the same two digits that
 *       begin the virtual-account number for that type, held as {@code invoice_type_va_code}. Reading
 *       it from there rather than from a second mapping means the bill number and the VA number can
 *       never disagree about what type a bill is.</li>
 * </ul>
 *
 * <p>An unmapped type fails loudly. Substituting a placeholder would mint a number in a shape the
 * institution's other systems cannot parse, and it would do so silently.
 */
public class DatedTypeCodeInvoiceNumberStrategy implements InvoiceNumberStrategy {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RunningNumberService runningNumbers;
    private final InvoiceTypeVaCodeRepository vaCodes;
    private final ArInvoiceProperties properties;
    private final int typeDigits;

    public DatedTypeCodeInvoiceNumberStrategy(RunningNumberService runningNumbers,
                                              InvoiceTypeVaCodeRepository vaCodes,
                                              ArInvoiceProperties properties,
                                              ArGatewayProperties gatewayProperties) {
        this.runningNumbers = runningNumbers;
        this.vaCodes = vaCodes;
        this.properties = properties;
        // The same width the VA number gives the same code. Taking it from one place is what stops
        // the two from drifting: a code stored as "3" renders as "03" in both, and every consumer
        // that reads the type by position keeps working.
        this.typeDigits = gatewayProperties.vaInvoiceTypeDigits();
    }

    private static String leftPad(String value, int width) {
        return value.length() >= width ? value : "0".repeat(width - value.length()) + value;
    }

    @Override
    public String next(InvoiceType type, LocalDate issueDate) {
        String code = vaCodes.findByInvoiceTypeCode(type.getCode())
                .orElseThrow(() -> new InvalidRequestException(
                        "Invoice type " + type.getCode() + " has no VA code, so no bill number can be"
                                + " formed for it — set one before issuing invoices of this type"))
                .getVaCode();
        String day = issueDate.format(DAY);
        long sequence = runningNumbers.nextValue(day);
        return day + leftPad(code, typeDigits)
                + String.format("%0" + properties.numberPadLength() + "d", sequence);
    }
}

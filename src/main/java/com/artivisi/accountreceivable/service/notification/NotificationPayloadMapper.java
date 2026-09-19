package com.artivisi.accountreceivable.service.notification;

import com.artivisi.accountreceivable.dto.NotificationRequest;

import java.util.Map;
import java.util.Set;

/**
 * Translates AR's own notification data into whatever the receiving hub expects.
 *
 * <p>A notification hub does not take "some JSON". It takes a named configuration whose template
 * declares required variables, and it refuses anything whose keys do not match — by name, exactly.
 * AR's names are its own ({@code debtorName}, {@code amount}, {@code dueDate}); an institution's hub
 * may have been rendering {@code nama}, {@code jumlah}, {@code tanggalTagihan} into the same emails
 * for years and will not be renamed to suit us.
 *
 * <p>So the payload is a deployment contract, not product vocabulary, and this is where that seam
 * lives. The product default passes AR's names through unchanged; a deployment whose hub expects
 * something else describes the mapping in configuration.
 *
 * <p>Learned the hard way: AR once published its own names at a hub that wanted different ones, and
 * every message was refused with a "required variable missing" error. Nobody saw it for weeks,
 * because the messages were also going to a topic with no consumer, so the refusal had nowhere to
 * appear.
 */
public interface NotificationPayloadMapper {

    /** AR's own bill-issued variables. {@code email}, {@code phone}, {@code description} may be absent. */
    Set<String> BILL_ISSUED_VARIABLES = Set.of(
            "debtorName", "invoiceNumber", "invoiceType", "amount", "currency", "dueDate", "vaNumber",
            "escrowCode", "issueDate", "email", "phone", "description");

    /**
     * AR's own payment-received variables. {@code paidAt} is an ISO instant; {@code paidAtLocal} is the
     * same moment as {@code yyyy-MM-dd HH:mm:ss} in the deployment's zone.
     */
    Set<String> PAYMENT_RECEIVED_VARIABLES = Set.of(
            "debtorName", "invoiceNumber", "paymentAmount", "currency", "cumulativePaid", "outstanding",
            "paymentReference", "paidAt", "paidAtLocal", "invoiceType", "invoiceAmount", "issueDate",
            "email", "phone");

    /** AR's own dunning variables. */
    Set<String> DUNNING_VARIABLES = Set.of(
            "debtorName", "invoiceNumber", "currency", "amountOutstanding", "dueDate", "daysOverdue");

    Map<String, String> billIssued(Map<String, String> generic);

    Map<String, String> paymentReceived(Map<String, String> generic);

    Map<String, String> dunning(Map<String, String> generic);

    /** The message as the hub reads it: the variables wrapped with config id and recipients. */
    Map<String, Object> envelope(NotificationRequest request);
}

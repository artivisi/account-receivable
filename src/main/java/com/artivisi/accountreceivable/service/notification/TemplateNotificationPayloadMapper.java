package com.artivisi.accountreceivable.service.notification;

import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.dto.NotificationRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps AR's variables onto a hub's names from configuration: {@code hubVariable → template}, where a
 * template is literal text with {@code {variable}} references, e.g.
 * {@code rekening: "Some Bank {vaNumber}"}. Deployment text (contact details, bank names) is literal
 * text in the template.
 *
 * <p>A key whose template references a variable that is absent or blank is left out, not sent empty:
 * the hub then names the missing variable in its own refusal, instead of rendering a blank into an
 * email. A reference to a variable AR does not produce for that event fails at startup — otherwise a
 * typo would reach the hub as a key that is never present.
 */
public class TemplateNotificationPayloadMapper implements NotificationPayloadMapper {

    private static final Pattern REFERENCE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private final ArNotificationProperties.Envelope envelope;
    private final List<Entry> billIssued;
    private final List<Entry> paymentReceived;
    private final List<Entry> dunning;

    public TemplateNotificationPayloadMapper(ArNotificationProperties.Template template) {
        this.envelope = template.envelope();
        this.billIssued = compile("bill-issued", template.billIssued(), BILL_ISSUED_VARIABLES);
        this.paymentReceived = compile("payment-received", template.paymentReceived(), PAYMENT_RECEIVED_VARIABLES);
        this.dunning = compile("dunning", template.dunning(), DUNNING_VARIABLES);
    }

    @Override
    public Map<String, String> billIssued(Map<String, String> generic) {
        return render(billIssued, generic);
    }

    @Override
    public Map<String, String> paymentReceived(Map<String, String> generic) {
        return render(paymentReceived, generic);
    }

    @Override
    public Map<String, String> dunning(Map<String, String> generic) {
        return render(dunning, generic);
    }

    @Override
    public Map<String, Object> envelope(NotificationRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(envelope.configId(), request.configId());
        out.put(envelope.email(), request.email());
        out.put(envelope.mobile(), request.mobile());
        out.put(envelope.data(), request.data());
        return out;
    }

    private static List<Entry> compile(String event, Map<String, String> templates, Set<String> known) {
        List<Entry> entries = new ArrayList<>();
        templates.forEach((key, template) -> {
            List<String> references = new ArrayList<>();
            Matcher m = REFERENCE.matcher(template);
            while (m.find()) {
                if (!known.contains(m.group(1))) {
                    throw new IllegalArgumentException("ar.notification.template." + event + "." + key
                            + " references {" + m.group(1) + "}, which AR does not produce for this event;"
                            + " available: " + known.stream().sorted().toList());
                }
                references.add(m.group(1));
            }
            entries.add(new Entry(key, template, references));
        });
        return List.copyOf(entries);
    }

    private static Map<String, String> render(List<Entry> entries, Map<String, String> generic) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String value = entry.render(generic);
            if (value != null) {
                out.put(entry.key(), value);
            }
        }
        return out;
    }

    private record Entry(String key, String template, List<String> references) {

        /** The rendered value, or null when a referenced variable is absent or blank. */
        String render(Map<String, String> generic) {
            for (String reference : references) {
                String value = generic.get(reference);
                if (value == null || value.isBlank()) {
                    return null;
                }
            }
            Matcher m = REFERENCE.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(generic.get(m.group(1))));
            }
            m.appendTail(out);
            return out.toString();
        }
    }
}

package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Command topics that each belong to one producer and reach only the invoice types it owns.
 *
 * <p>The shared command topic cannot say who sent a message: {@code producer} in the envelope is
 * whatever the sender wrote, and every application allowed onto the broker can write to it. Since
 * {@code payment.recorded} exists, that means any of them can mark any payer's invoice paid and close
 * its VA, whatever the invoice is for. Broker permissions do not help on a shared topic, because they
 * are granted per topic. A topic per producer does: the broker decides who may write to it, and AR
 * decides what a message arriving on it may touch.
 *
 * <p>Parsed once at startup from {@code ar.contract.scoped-command-topics}, and refused if malformed —
 * a typo here would otherwise either open a topic to everything or close it silently.
 */
@Component
public class ScopedCommandTopics {

    private final Map<String, Set<String>> scopes;

    public ScopedCommandTopics(ArContractProperties properties) {
        this.scopes = parse(properties.scopedCommandTopics(), properties.topics());
    }

    static Map<String, Set<String>> parse(String spec, ArContractProperties.Topics shared) {
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        if (spec.isBlank()) {
            return parsed;
        }
        for (String entry : spec.split(";")) {
            String[] parts = entry.split("=", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("ar.contract.scoped-command-topics: '" + entry
                        + "' is not topic=CODE[,CODE]");
            }
            String topic = parts[0].strip();
            if (shared != null && (topic.equals(shared.invoiceCommand()) || topic.equals(shared.debtorCommand()))) {
                throw new IllegalStateException("ar.contract.scoped-command-topics: " + topic
                        + " is a shared command topic; a scoped topic must be one producer's own");
            }
            Set<String> codes = new LinkedHashSet<>();
            for (String code : parts[1].split(",")) {
                if (code.isBlank()) {
                    throw new IllegalStateException("ar.contract.scoped-command-topics: empty invoice type code for " + topic);
                }
                codes.add(code.strip());
            }
            if (parsed.put(topic, Set.copyOf(codes)) != null) {
                throw new IllegalStateException("ar.contract.scoped-command-topics: " + topic + " is listed twice");
            }
        }
        return parsed;
    }

    /** Every scoped topic, for the listener to subscribe to. */
    public String[] names() {
        return scopes.keySet().toArray(String[]::new);
    }

    public Optional<CommandScope> scopeOf(String topic) {
        return Optional.ofNullable(scopes.get(topic)).map(codes -> CommandScope.of(topic, codes));
    }

    /**
     * What a command may touch: everything, when it arrived on a shared topic, or only the listed
     * invoice types. An explicit flag rather than a null set, so "no restriction" is never something
     * a missing value means by accident.
     */
    public record CommandScope(String topic, boolean unrestricted, Set<String> invoiceTypes) {

        public static CommandScope shared(String topic) {
            return new CommandScope(topic, true, Set.of());
        }

        public static CommandScope of(String topic, Set<String> invoiceTypes) {
            return new CommandScope(topic, false, invoiceTypes);
        }

        public boolean permits(String invoiceTypeCode) {
            return unrestricted || invoiceTypes.contains(invoiceTypeCode);
        }

        @Override
        public String toString() {
            return unrestricted ? topic : topic + " " + Arrays.toString(invoiceTypes.stream().sorted().toArray());
        }
    }
}

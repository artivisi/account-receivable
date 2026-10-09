package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.entity.ContractCommand;
import com.artivisi.accountreceivable.entity.ContractFailure;
import com.artivisi.accountreceivable.repository.ContractCommandRepository;
import com.artivisi.accountreceivable.repository.ContractFailureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Takes one raw message off a command topic and turns it into either an executed command with its
 * answering event, a rejection event, or a recorded failure — never an exception back to the
 * listener, so the partition always moves on.
 *
 * <p>Order of checks: parse and validate against the published schema (the same file the upstream
 * teams build against); repeat of a known {@code idempotencyKey} republishes the stored answer;
 * otherwise execute. The listener is a thin Kafka adapter over {@link #handle}, which is what tests
 * drive directly.
 */
@Service
public class ContractCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ContractCommandHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "classpath:contracts/v2/messages.schema.json";

    private final ContractCommandService commands;
    private final ContractEventService events;
    private final ContractCommandRepository commandRepository;
    private final ContractFailureRepository failureRepository;
    private final Clock clock;
    private final JsonSchemaFactory schemaFactory;
    private final SchemaValidatorsConfig schemaConfig;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public ContractCommandHandler(ContractCommandService commands, ContractEventService events,
                                  ContractCommandRepository commandRepository,
                                  ContractFailureRepository failureRepository,
                                  Clock clock) {
        this.commands = commands;
        this.events = events;
        this.commandRepository = commandRepository;
        this.failureRepository = failureRepository;
        this.clock = clock;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.schemaConfig = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    }

    public void handle(String topic, String message) {
        JsonNode root;
        try {
            root = JSON.readTree(message);
        } catch (Exception e) {
            reject(null, null, "SCHEMA_INVALID", "message is not JSON: " + e.getMessage());
            return;
        }
        String type = root.path("type").asText(null);
        JsonNode payload = root.path("payload");
        String idempotencyKey = payload.path("idempotencyKey").asText(null);
        String debtorCode = payload.isObject() ? commands.messageKeyFor(payload) : null;
        String correlationId = idempotencyKey != null ? idempotencyKey : root.path("eventId").asText(null);

        if (type == null || !type.matches("[a-z]+\\.[A-Za-z]+")) {
            reject(debtorCode, correlationId, "SCHEMA_INVALID", "missing or malformed type");
            return;
        }
        Set<ValidationMessage> errors;
        try {
            errors = schemaFor("command." + type).validate(root);
        } catch (Exception e) {
            reject(debtorCode, correlationId, "SCHEMA_INVALID", "unknown command type " + type);
            return;
        }
        if (!errors.isEmpty()) {
            reject(debtorCode, correlationId, "SCHEMA_INVALID",
                    errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
            return;
        }

        if (idempotencyKey != null) {
            ContractCommand known = commandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (known != null) {
                if (known.getResultPayload() != null) {
                    events.republish(known.getResultTopic(), known.getMessageKey(),
                            JSON_TYPE(known.getResultPayload()), known.getResultPayload());
                }
                log.info("Repeat of {} {} answered from the idempotency store", type, idempotencyKey);
                return;
            }
        }

        try {
            ContractCommandService.CommandResult result =
                    commands.execute(type, payload, root.path("producer").asText("unknown"));
            remember(idempotencyKey, type, result.messageKey(), result.resultTopic(), result.resultPayload());
        } catch (ContractRejectedException e) {
            // Answered, but deliberately not remembered. Idempotency exists so a repeat cannot create
            // a second invoice; a rejection created nothing, so re-running one risks nothing either.
            // Remembering it made the opposite true: a command refused because of a defect on OUR
            // side stayed refused after the defect was fixed, because the sender's key is usually
            // derived from the invoice and a natural retry reproduces it exactly. Upstream then reads
            // a stale answer as proof the fix did not work. The rejection is still published and
            // still on the event topic; the store is an idempotency ledger, not the history.
            reject(debtorCode, correlationId, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Command {} on {} failed past validation: {}", type, topic, e.toString());
            recordFailure(topic, debtorCode, message, e);
        }
    }

    private JsonSchema schemaFor(String def) {
        return schemas.computeIfAbsent(def, d -> schemaFactory.getSchema(SchemaLocation.of(SCHEMA + "#/$defs/" + d), schemaConfig));
    }

    private static String JSON_TYPE(String payload) {
        try {
            return JSON.readTree(payload).path("type").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Not transactional itself: the event service's own transaction persists the rejection. */
    protected String reject(String debtorCode, String correlationId, String code, String reason) {
        log.warn("Rejecting command {} with {}: {}", correlationId, code, reason);
        return events.invoiceRejected(debtorCode, correlationId, code, reason);
    }

    protected void remember(String idempotencyKey, String type, String debtorCode, String topic, String result) {
        if (idempotencyKey == null) {
            return;
        }
        ContractCommand row = new ContractCommand();
        row.setIdempotencyKey(idempotencyKey);
        row.setCommandType(type);
        row.setMessageKey(debtorCode == null ? idempotencyKey : debtorCode);
        row.setResultTopic(topic);
        row.setResultPayload(result);
        row.setReceivedAt(Instant.now(clock));
        commandRepository.save(row);
    }

    protected void recordFailure(String topic, String key, String message, Exception e) {
        ContractFailure row = new ContractFailure();
        row.setTopic(topic);
        row.setMessageKey(key);
        row.setPayload(message);
        row.setError(e.toString());
        row.setResolved(false);
        failureRepository.save(row);
    }
}

package com.artivisi.accountreceivable.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The fields of a gateway refusal, read from its ProblemDetail body.
 *
 * <p>AR decides on {@code code}, never on the sentence. It used to tell a busy VA number from any
 * other conflict by searching the detail for "vaNumber already active", which made the gateway's
 * wording part of the contract; and since AR no longer composes the number, the refusal is also
 * the only place it can learn which number collided and which of its own charges holds it.
 *
 * <p>A field the gateway did not send is null. {@code code} is null for a refusal the gateway does
 * not code, which a caller treats as one it does not know how to resolve.
 */
public record GatewayProblem(
        String code,
        String detail,
        String escrowCode,
        String vaNumber,
        String heldByConsumerReference,
        String field
) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static GatewayProblem of(HttpClientErrorException e) {
        JsonNode body;
        try {
            body = JSON.readTree(e.getResponseBodyAsString());
        } catch (Exception notJson) {
            throw new IllegalStateException("gateway answered " + e.getStatusCode()
                    + " with a body that is not a ProblemDetail: " + e.getResponseBodyAsString(), e);
        }
        return new GatewayProblem(text(body, "code"), text(body, "detail"), text(body, "escrowCode"),
                text(body, "vaNumber"), text(body, "heldByConsumerReference"), text(body, "field"));
    }

    private static String text(JsonNode body, String name) {
        JsonNode node = body.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }
}

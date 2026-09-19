package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.config.HmacSignature;
import com.artivisi.accountreceivable.dto.CashApplicationResponse;
import com.artivisi.accountreceivable.dto.GatewayWebhookPayload;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.UnauthorizedException;
import com.artivisi.accountreceivable.service.CollectionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives gateway payment webhooks. The raw body is HMAC-verified before any processing; an
 * invalid or missing signature is rejected (401) and nothing is applied.
 */
@RestController
public class GatewayWebhookController {

    private final CollectionService collectionService;
    private final HmacSignature hmacSignature;
    private final ArGatewayProperties gatewayProperties;
    private final ObjectMapper objectMapper;

    public GatewayWebhookController(CollectionService collectionService,
                                    HmacSignature hmacSignature,
                                    ArGatewayProperties gatewayProperties,
                                    ObjectMapper objectMapper) {
        this.collectionService = collectionService;
        this.hmacSignature = hmacSignature;
        this.gatewayProperties = gatewayProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/gateway")
    public CashApplicationResponse receive(@RequestBody String rawBody,
                                           @RequestHeader(value = "X-Signature", required = false) String signature) {
        if (!hmacSignature.verify(gatewayProperties.clientSecret(), rawBody, signature)) {
            throw new UnauthorizedException("Invalid webhook signature");
        }
        GatewayWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, GatewayWebhookPayload.class);
        } catch (Exception e) {
            throw new InvalidRequestException("Malformed webhook body");
        }
        try {
            return collectionService.applyPayment(payload);
        } catch (DataIntegrityViolationException raced) {
            // Concurrent redelivery of the same payment won the unique-reference race and committed
            // first; the payment is applied exactly once. Return it idempotently instead of 500.
            return collectionService.getAppliedByReference(payload.bankReference());
        }
    }
}

package com.artivisi.accountreceivable.service.notification;

import com.artivisi.accountreceivable.dto.NotificationRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The product default: AR's own names, unchanged. Correct for a hub built against AR, and the only
 * honest default — inventing a translation for a hub we have never seen would be guessing.
 */
public class PassthroughNotificationPayloadMapper implements NotificationPayloadMapper {

    @Override
    public Map<String, String> billIssued(Map<String, String> generic) {
        return generic;
    }

    @Override
    public Map<String, String> paymentReceived(Map<String, String> generic) {
        return generic;
    }

    @Override
    public Map<String, String> dunning(Map<String, String> generic) {
        return generic;
    }

    @Override
    public Map<String, Object> envelope(NotificationRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configId", request.configId());
        out.put("email", request.email());
        out.put("mobile", request.mobile());
        out.put("data", request.data());
        return out;
    }
}

package com.artivisi.accountreceivable.dto;

import java.util.Map;

/**
 * One notification as AR sees it: the hub config id selecting the template set, the recipients, and
 * the variables (all String). Channel is chosen by recipient presence ({@code email}/{@code mobile})
 * and by template presence in the hub config; there is no channel field. The field names the hub
 * reads come from {@code NotificationPayloadMapper#envelope}, not from this record.
 */
public record NotificationRequest(
        String configId,
        String email,
        String mobile,
        Map<String, String> data
) {
}

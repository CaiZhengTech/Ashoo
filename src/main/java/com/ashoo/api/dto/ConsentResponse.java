package com.ashoo.api.dto;

import com.ashoo.storage.entity.ConsentRecord;

import java.time.Instant;

/**
 * API view of the user's consent status.
 *
 * Returns whether consent exists plus, when it does, the exact text accepted and when —
 * so the frontend can show "you accepted this on [date]" and gate the reminder UI.
 */
public record ConsentResponse(
        boolean consented,
        Instant consentedAt,
        String disclaimerText
) {
    /**
     * Builds a "consented" response from a stored record.
     *
     * @param record the consent record
     * @return a populated response
     */
    public static ConsentResponse of(ConsentRecord record) {
        return new ConsentResponse(true, record.getConsentedAt(), record.getDisclaimerText());
    }

    /**
     * @return a response indicating the user has not yet consented
     */
    public static ConsentResponse notConsented() {
        return new ConsentResponse(false, null, null);
    }
}

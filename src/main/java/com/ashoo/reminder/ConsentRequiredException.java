package com.ashoo.reminder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a reminder or medication feature is accessed before the user has
 * accepted the advisory disclaimer.
 *
 * Annotated with {@code @ResponseStatus(FORBIDDEN)} so that any uncaught instance
 * becomes a clean HTTP 403 without each controller needing its own try/catch — the
 * gate is enforced once, at the service layer, and surfaced consistently.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException() {
        super("Consent required: accept the advisory disclaimer (POST /api/v1/consent) "
                + "before using reminder or medication features.");
    }
}

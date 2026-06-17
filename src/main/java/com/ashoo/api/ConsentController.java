package com.ashoo.api;

import com.ashoo.api.dto.ConsentResponse;
import com.ashoo.reminder.ConsentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints for accepting and checking the advisory-only disclaimer.
 *
 * Accepting consent here is the prerequisite that unlocks all medication and reminder
 * features. The accepted text is returned so the frontend can display exactly what the
 * user agreed to.
 */
@RestController
@RequestMapping("/api/v1/consent")
public class ConsentController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * Records the user's acceptance of the disclaimer.
     *
     * @return the stored consent (text + timestamp) with 201 Created
     */
    @PostMapping
    public ResponseEntity<ConsentResponse> accept() {
        return ResponseEntity
                .status(201)
                .body(ConsentResponse.of(consentService.recordConsent(DEFAULT_USER)));
    }

    /**
     * Returns whether the user has consented, and if so the accepted text and timestamp.
     *
     * @return the consent status
     */
    @GetMapping
    public ConsentResponse status() {
        return consentService.getConsent(DEFAULT_USER)
                .map(ConsentResponse::of)
                .orElseGet(ConsentResponse::notConsented);
    }
}

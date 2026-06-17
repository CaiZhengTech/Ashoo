package com.ashoo.briefing;

import org.springframework.stereotype.Component;

/**
 * Guarantees the mandatory closing disclaimer is present on every briefing.
 *
 * This runs as a post-processing step <em>after</em> the Claude API response. The system
 * prompt also instructs the model to end with the disclaimer, but we never trust model
 * compliance for a safety requirement — this injector enforces it deterministically. If
 * the model already ended with the exact sentence, we don't duplicate it; otherwise we
 * append it.
 *
 * The disclaimer is a compile-time constant, never loaded from config or a database, so
 * it cannot be altered or removed at runtime without a code review.
 */
@Component
public class BriefingDisclaimerInjector {

    /** The exact closing sentence every briefing must end with. */
    public static final String DISCLAIMER = "As always, consult your doctor for medical decisions.";

    /**
     * Returns the briefing text with the disclaimer guaranteed present at the end.
     *
     * @param briefingText the raw text from the model (or fallback template); may be null
     * @return the text ending with the disclaimer, never duplicated
     */
    public String injectDisclaimer(String briefingText) {
        String text = briefingText == null ? "" : briefingText.strip();
        if (text.contains(DISCLAIMER)) {
            return text;
        }
        if (text.isEmpty()) {
            return DISCLAIMER;
        }
        return text + " " + DISCLAIMER;
    }
}

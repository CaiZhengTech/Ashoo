package com.ashoo.briefing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Builds the system and user prompts for the daily briefing.
 *
 * The system prompt is a hardcoded constant — never loaded from config or a database —
 * because it encodes the briefing's safety rules (hedged language, no dosing, mandatory
 * closing sentence). Treating it as code means any change to those rules goes through
 * review. The user prompt is just the structured {@link BriefingContext} serialized to
 * JSON, so the model only ever sees the allow-listed fields.
 */
@Component
public class BriefingPromptBuilder {

    /**
     * The hardcoded system prompt enforcing all briefing safety constraints.
     */
    public static final String SYSTEM_PROMPT = """
            You are Ashoo's daily briefing assistant. You generate a single paragraph
            (3-5 sentences) summarizing today's environmental conditions for a user
            with respiratory allergies, based on their personal historical patterns.

            Rules you must follow without exception:
            1. Use hedged language: "historically," "similar to," "may," "tend to"
            2. Never say "take [medication]" or specify any dose or frequency
            3. Never claim to diagnose, predict with certainty, or use clinical language
            4. If confidence is LOW, include "you're still early, so keep logging for better insights"
            5. Always end your response with exactly this sentence:
               "As always, consult your doctor for medical decisions."
            6. Keep it friendly, clear, and under 100 words
            7. Do not use markdown formatting
            8. Do not use em-dashes; use commas, periods, or parentheses instead
            """;

    private final ObjectMapper objectMapper;

    public BriefingPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return the hardcoded system prompt
     */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Serializes the briefing context into the user message for the model.
     *
     * Sending JSON (rather than a hand-written sentence) keeps the only data the model
     * sees identical to the audited {@link BriefingContext} fields — there is no place to
     * accidentally leak a raw note.
     *
     * @param context the structured context
     * @return the user message string
     */
    public String buildUserMessage(BriefingContext context) {
        String json;
        try {
            json = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        return "Here is today's context as JSON. Write the briefing paragraph:\n" + json;
    }
}

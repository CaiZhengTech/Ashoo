package com.ashoo.briefing;

import com.ashoo.common.AshooProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client for the Anthropic Claude Messages API, used to write the daily briefing.
 *
 * Returns {@link Optional} rather than throwing on failure: an empty result means "the
 * model was unavailable, use the fallback template." This keeps the briefing feature
 * resilient — a missing API key (as in CI/local dev), a network blip, or a rate limit
 * degrades gracefully to a deterministic template instead of breaking the dashboard.
 *
 * Uses Spring {@link RestClient} (synchronous, virtual-thread friendly) per the project's
 * HTTP conventions.
 */
@Component
public class AnthropicClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public AnthropicClient(AshooProperties props) {
        AshooProperties.AnthropicConfig cfg = props.anthropic();
        this.apiKey = cfg.apiKey();
        this.model = cfg.model();
        this.maxTokens = cfg.maxTokens();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .build();
    }

    /**
     * Whether a real API call is possible (an API key is configured).
     *
     * @return true if an API key is present
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Calls Claude with the given system and user prompts.
     *
     * @param systemPrompt the hardcoded safety system prompt
     * @param userMessage  the structured-context user message
     * @return the generated text and output-token count, or empty to signal "use fallback"
     */
    public Optional<ClaudeResult> generateText(String systemPrompt, String userMessage) {
        if (!isConfigured()) {
            log.debug("No Anthropic API key configured, using fallback briefing template");
            return Optional.empty();
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userMessage)));

            MessagesResponse response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                log.warn("Anthropic API returned no content, using fallback");
                return Optional.empty();
            }

            String text = response.content().stream()
                    .filter(c -> "text".equals(c.type()))
                    .map(ContentBlock::text)
                    .reduce("", String::concat);
            int tokens = response.usage() != null ? response.usage().outputTokens() : 0;
            return Optional.of(new ClaudeResult(text, tokens));

        } catch (RestClientException e) {
            log.error("Anthropic API call failed, using fallback: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** A successful generation: the text and how many output tokens it used. */
    public record ClaudeResult(String text, int tokensUsed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagesResponse(List<ContentBlock> content, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(String type, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("output_tokens") int outputTokens) {}
}

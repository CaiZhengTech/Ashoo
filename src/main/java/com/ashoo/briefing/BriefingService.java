package com.ashoo.briefing;

import com.ashoo.briefing.AnthropicClient.ClaudeResult;
import com.ashoo.storage.entity.BriefingLog;
import com.ashoo.storage.repository.BriefingLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates daily-briefing generation: context → prompt → model (or fallback) →
 * mandatory disclaimer → audit log.
 *
 * The disclaimer is injected after generation regardless of source, so whether the text
 * came from Claude or the offline fallback template, it always ends with the required
 * sentence. The "today" path reuses a same-day briefing so each page load doesn't
 * regenerate (and re-bill) one.
 */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    private final BriefingContextBuilder contextBuilder;
    private final BriefingPromptBuilder promptBuilder;
    private final AnthropicClient anthropicClient;
    private final BriefingDisclaimerInjector disclaimerInjector;
    private final BriefingLogRepository briefingLogRepo;

    public BriefingService(BriefingContextBuilder contextBuilder,
                           BriefingPromptBuilder promptBuilder,
                           AnthropicClient anthropicClient,
                           BriefingDisclaimerInjector disclaimerInjector,
                           BriefingLogRepository briefingLogRepo) {
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.anthropicClient = anthropicClient;
        this.disclaimerInjector = disclaimerInjector;
        this.briefingLogRepo = briefingLogRepo;
    }

    /**
     * Returns today's briefing, reusing a same-day one if it exists.
     *
     * Caching by calendar day keeps cost and latency down without staleness mattering —
     * a briefing summarizes the day, so one per day is the right granularity.
     *
     * @param userId the user
     * @param isDemo whether this is a demo-persona briefing (logged separately)
     * @return the briefing (cached or freshly generated)
     */
    public BriefingResult getTodayBriefing(String userId, boolean isDemo) {
        Optional<BriefingLog> latest = briefingLogRepo.findLatestByUserId(userId);
        if (latest.isPresent() && isToday(latest.get().getGeneratedAt())) {
            BriefingLog l = latest.get();
            return new BriefingResult(l.getBriefingText(),
                    l.getTokensUsed() != null ? l.getTokensUsed() : 0,
                    l.getRiskScore() != null ? l.getRiskScore() : 0.0,
                    l.getRiskLabel(), "cached", l.getGeneratedAt());
        }
        return generateBriefing(userId, isDemo);
    }

    /**
     * Generates a fresh briefing, calling Claude when configured and falling back to a
     * deterministic template otherwise.
     *
     * @param userId the user
     * @param isDemo whether this is a demo-persona briefing
     * @return the generated briefing, always ending with the mandatory disclaimer
     */
    public BriefingResult generateBriefing(String userId, boolean isDemo) {
        BriefingContext context = contextBuilder.buildContext(userId);

        String rawText;
        int tokens;
        String source;
        Optional<ClaudeResult> claude = anthropicClient.generateText(
                promptBuilder.systemPrompt(), promptBuilder.buildUserMessage(context));
        if (claude.isPresent()) {
            rawText = claude.get().text();
            tokens = claude.get().tokensUsed();
            source = "claude";
        } else {
            rawText = fallbackTemplate(context);
            tokens = 0;
            source = "fallback";
        }

        String finalText = disclaimerInjector.injectDisclaimer(rawText);
        Instant now = Instant.now();
        persist(userId, context, finalText, tokens, isDemo, now);

        log.info("Generated briefing for {} (source={}, {} tokens)", userId, source, tokens);
        return new BriefingResult(finalText, tokens, context.riskScore(),
                context.riskLabel(), source, now);
    }

    /**
     * Builds a deterministic, hedged briefing when the AI is unavailable.
     *
     * Mirrors the safety constraints of the system prompt in code: hedged language, no
     * dosing, and the LOW-confidence "keep logging" nudge. The disclaimer is appended
     * separately by the injector, so it is intentionally omitted here.
     */
    private String fallbackTemplate(BriefingContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today in ").append(c.location())
                .append(", your personal risk index is around ").append(c.riskScore())
                .append("/100 (").append(c.riskLabel()).append("). ");

        // Lead with what's actually driving it: factors above the user's learned thresholds
        // are the ones that have historically preceded their symptom days.
        List<BriefingContext.TopFactor> elevated = c.topFactors().stream()
                .filter(BriefingContext.TopFactor::abovePersonalThreshold)
                .limit(3)
                .toList();

        if (!elevated.isEmpty()) {
            String names = joinNames(elevated);
            sb.append(names).append(elevated.size() == 1 ? " is" : " are")
                    .append(" running above the level that has preceded your symptom days before");
            BriefingContext.TopFactor lead = elevated.getFirst();
            sb.append(" (").append(lead.name().toLowerCase())
                    .append(" near the ").append((int) Math.round(lead.percentile()))
                    .append("th percentile of your own history). ");
        } else if (!c.topFactors().isEmpty()) {
            BriefingContext.TopFactor top = c.topFactors().getFirst();
            sb.append("Nothing is crossing your personal thresholds right now, ")
                    .append(top.name().toLowerCase())
                    .append(" is the most notable factor at the ")
                    .append((int) Math.round(top.percentile()))
                    .append("th percentile of what you've seen. ");
        }

        // Connect to how they've actually been feeling lately.
        long recent = c.recentSymptomDays() == null ? 0
                : c.recentSymptomDays().stream().filter(s -> s.severity() >= 1).count();
        if (recent > 0) {
            sb.append("You've logged ").append(recent)
                    .append(recent == 1 ? " symptom day" : " symptom days")
                    .append(" in the past week, so it may be worth staying aware. ");
        }

        if ("LOW".equals(c.confidence())) {
            sb.append("You're still early, so keep logging for sharper, more personal insights.");
        } else {
            sb.append("This reflects patterns learned from ").append(c.symptomDaysAvailable())
                    .append(" of your logged symptom days.");
        }
        return sb.toString().trim();
    }

    /** Joins factor display names into a readable list ("A, B and C"). */
    private static String joinNames(List<BriefingContext.TopFactor> factors) {
        List<String> names = factors.stream().map(BriefingContext.TopFactor::name).toList();
        if (names.size() == 1) return names.getFirst();
        if (names.size() == 2) return names.get(0) + " and " + names.get(1);
        return String.join(", ", names.subList(0, names.size() - 1))
                + " and " + names.getLast();
    }

    private void persist(String userId, BriefingContext context, String text,
                         int tokens, boolean isDemo, Instant now) {
        BriefingLog logRow = new BriefingLog();
        logRow.setUserId(userId);
        logRow.setGeneratedAt(now);
        logRow.setRiskScore((double) context.riskScore());
        logRow.setRiskLabel(context.riskLabel());
        logRow.setBriefingText(text);
        logRow.setTokensUsed(tokens);
        logRow.setIsDemo(isDemo);
        briefingLogRepo.save(logRow);
    }

    private static boolean isToday(Instant instant) {
        if (instant == null) return false;
        return instant.atZone(ZoneOffset.UTC).toLocalDate()
                .equals(Instant.now().atZone(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * The result of a briefing request.
     *
     * @param text        the briefing text, always ending with the mandatory disclaimer
     * @param tokensUsed  output tokens used (0 for fallback/cached)
     * @param riskScore   the risk score the briefing was based on
     * @param riskLabel   the risk label
     * @param source      where the text came from: "claude", "fallback", or "cached"
     * @param generatedAt when it was generated
     */
    public record BriefingResult(String text, int tokensUsed, double riskScore,
                                 String riskLabel, String source, Instant generatedAt) {}
}

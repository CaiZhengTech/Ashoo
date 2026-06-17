package com.ashoo.briefing;

import java.util.List;

/**
 * The controlled, structured payload sent to the Claude API for a daily briefing.
 *
 * We never send raw database rows or the user's free-text symptom notes to an external
 * service — those may contain personal details the user never intended to share. This
 * record is the allow-list: only the structured fields the briefing actually needs. The
 * absence of any free-text field is a deliberate privacy guarantee enforced by the type
 * itself, not by remembering to strip notes later.
 *
 * @param riskScore             today's smoothed Personal Risk Index (0–100)
 * @param riskLabel             the bucket label (Great…Severe)
 * @param topFactors            the few strongest contributing factors today
 * @param recentSymptomDays     the last 7 days of symptoms as severity + medication types only
 * @param registeredMedicationTypes the user's medication categories (types, not names)
 * @param location              the user's primary location display name
 * @param confidence            data confidence level (LOW/MEDIUM/HIGH)
 * @param symptomDaysAvailable  how many symptom days back the model
 */
public record BriefingContext(
        int riskScore,
        String riskLabel,
        List<TopFactor> topFactors,
        List<RecentSymptom> recentSymptomDays,
        List<String> registeredMedicationTypes,
        String location,
        String confidence,
        int symptomDaysAvailable
) {
    /**
     * A contributing factor in the briefing context.
     *
     * @param name            human-readable factor name (e.g. "Grass pollen")
     * @param percentile      today's value as a 0–100 percentile of the user's history
     * @param abovePersonalThreshold whether it crossed the learned personal threshold
     */
    public record TopFactor(String name, double percentile, boolean abovePersonalThreshold) {}

    /**
     * A recent symptom day, reduced to non-identifying structured fields.
     *
     * @param daysAgo         how many days ago (0 = today)
     * @param severity        logged severity 0–10
     * @param medicationsUsed medication <em>types</em> used (e.g. ["INHALER"]), never names
     */
    public record RecentSymptom(int daysAgo, int severity, List<String> medicationsUsed) {}
}

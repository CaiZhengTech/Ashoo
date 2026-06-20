                                package com.ashoo.correlation;

/**
 * How much to trust a correlation result, based on how many symptom days
 * the user has logged.
 *
 * Statistical correlation needs enough positive examples (symptom days) to be
 * meaningful. With only a handful of symptom days, any pattern we find could be
 * coincidence. Rather than hide this uncertainty, Ashoo surfaces it directly —
 * the UI always shows the confidence level next to any score, so the user knows
 * whether the engine is guessing or has a real personal baseline.
 *
 * Thresholds are locked in CLAUDE.md: LOW &lt; 10, MEDIUM 10–29, HIGH 30+.
 */
public enum ConfidenceLevel {

    LOW("Estimates only, keep logging"),
    MEDIUM("Patterns emerging, thresholds improving"),
    HIGH("Strong personal baseline established");

    private final String message;

    ConfidenceLevel(String message) {
        this.message = message;
    }

    /**
     * Maps a symptom-day count to its confidence level.
     *
     * We use symptom days (not total days) because the positive class is what
     * limits statistical power: you can have a year of environmental data, but if
     * only 4 days had symptoms, you cannot reliably learn what triggers them.
     *
     * @param symptomDays number of distinct days the user logged severity &gt;= 1
     * @return LOW (&lt;10), MEDIUM (10–29), or HIGH (30+)
     */
    public static ConfidenceLevel fromSymptomDays(int symptomDays) {
        if (symptomDays < 10) return LOW;
        if (symptomDays < 30) return MEDIUM;
        return HIGH;
    }

    /**
     * @return a short user-facing explanation of what this confidence level means
     */
    public String getMessage() {
        return message;
    }
}

package com.ashoo.common;

/**
 * Maps a Personal Risk Index (0–100) to a human-readable label and color.
 *
 * The PRI is personal — an 80 means "conditions match YOUR worst days,"
 * not that the air is objectively dangerous for everyone. This enum keeps
 * the mapping between score ranges, labels, and hex colors in one place
 * so no other code needs to hard-code threshold boundaries.
 */
public enum RiskLevel {

    GREAT(0, 20, "Great", "#22C55E",
            "Conditions look good for you today"),
    MODERATE(21, 40, "Moderate", "#EAB308",
            "Minor irritants present — most people fine"),
    ELEVATED(41, 60, "Elevated", "#F97316",
            "Conditions match some of your past symptom days"),
    HIGH(61, 80, "High", "#EF4444",
            "Conditions closely match your past flare days — have meds handy"),
    SEVERE(81, 100, "Severe", "#A855F7",
            "Conditions match your worst recorded days — limit outdoor exposure");

    private final int min;
    private final int max;
    private final String label;
    private final String color;
    private final String guidance;

    RiskLevel(int min, int max, String label, String color, String guidance) {
        this.min = min;
        this.max = max;
        this.label = label;
        this.color = color;
        this.guidance = guidance;
    }

    /**
     * Finds the risk level for a given Personal Risk Index score.
     *
     * Clamps the input to [0, 100] so callers never need to bounds-check.
     * The five levels partition the full range with no gaps, so this method
     * always returns a result.
     *
     * @param pri the Personal Risk Index value (0.0–100.0)
     * @return the corresponding RiskLevel
     */
    public static RiskLevel fromScore(double pri) {
        int clamped = (int) Math.max(0, Math.min(100, Math.round(pri)));
        for (RiskLevel level : values()) {
            if (clamped >= level.min && clamped <= level.max) {
                return level;
            }
        }
        return SEVERE;
    }

    public String getLabel()    { return label; }
    public String getColor()    { return color; }
    public String getGuidance() { return guidance; }
    public int    getMin()      { return min; }
    public int    getMax()      { return max; }
}

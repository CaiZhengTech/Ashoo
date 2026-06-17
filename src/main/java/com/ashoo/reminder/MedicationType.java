package com.ashoo.reminder;

/**
 * The fixed set of medication categories the app offers in its type dropdown.
 *
 * Ashoo never suggests <em>which</em> medication a user should register — that would
 * stray into medical advice. It only constrains the <em>category</em> to a known list
 * so reminders and usage stats can group sensibly (e.g. "rescue inhaler used 4×"). The
 * user's actual medication name is free text on the {@link com.ashoo.storage.entity.Medication}.
 *
 * Keeping this as an enum (rather than a free string) lets the controller reject a
 * typo'd type at the boundary instead of storing garbage that breaks grouping later.
 */
public enum MedicationType {
    INHALER,
    ANTIHISTAMINE,
    EPINEPHRINE,
    NASAL_SPRAY,
    EYE_DROPS,
    OTHER;

    /**
     * Parses a type string, case-insensitively, returning null if it is not a known type.
     *
     * Returning null rather than throwing lets the controller decide the HTTP response
     * (a 400 with a helpful message) instead of leaking an enum exception.
     *
     * @param value the raw type string from a request
     * @return the matching MedicationType, or null if unrecognized
     */
    public static MedicationType fromString(String value) {
        if (value == null) return null;
        for (MedicationType t : values()) {
            if (t.name().equalsIgnoreCase(value.trim())) return t;
        }
        return null;
    }
}

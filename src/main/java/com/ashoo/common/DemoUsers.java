package com.ashoo.common;

import java.util.Set;

/**
 * Resolves the optional {@code user} request parameter to a safe, known user id.
 *
 * V1 is single-user, but the recruiter demo lets a viewer switch between the
 * default user and the three seeded personas to see how the same engine behaves
 * for different sensitivities. Centralizing the allow-list here means no endpoint
 * can be coaxed into reading an arbitrary user id from the query string — anything
 * unrecognized falls back to the default user. Each user now owns its own
 * environmental history (every demo persona lives in a distinct city), so the
 * environment owner for a user is resolved via {@link #envFor(String)}.
 */
public final class DemoUsers {

    private DemoUsers() {}

    /** The single real user the app is built around. */
    public static final String DEFAULT_USER = "ashoo-user";

    /**
     * The environment owner for a given user.
     *
     * Each user owns its own environmental snapshots now that demo personas live in
     * distinct cities, so this is the identity. It stays a named method so the
     * per-user environment design is explicit at every call site, and so env
     * ownership could be re-centralized in one place if that ever changes again.
     *
     * @param userId an already-resolved user id
     * @return the user id whose environmental snapshots this user reads
     */
    public static String envFor(String userId) {
        return userId;
    }

    /** Every user id a request is permitted to read. */
    private static final Set<String> ALLOWED =
            Set.of(DEFAULT_USER, "demo-alex", "demo-jordan", "demo-morgan");

    /** The seeded personas, in display order — used by seeding and the demo switcher. */
    public static final java.util.List<String> PERSONAS =
            java.util.List.of("alex", "jordan", "morgan");

    /**
     * Maps an incoming {@code user} value to an allowed id, defaulting safely.
     *
     * Accepts either the full id ("demo-morgan") or the short persona key ("morgan"),
     * so the frontend can pass whichever is convenient. Unknown values resolve to the
     * default user rather than erroring — a bad query param should never leak data or
     * 500 the dashboard.
     *
     * @param user the raw request value (may be null/blank/short key/full id)
     * @return a guaranteed-valid user id
     */
    public static String resolve(String user) {
        if (user == null || user.isBlank()) return DEFAULT_USER;
        String trimmed = user.trim();
        String candidate = trimmed.startsWith("demo-") ? trimmed : "demo-" + trimmed;
        if (ALLOWED.contains(candidate)) return candidate;
        if (ALLOWED.contains(trimmed)) return trimmed;
        return DEFAULT_USER;
    }
}

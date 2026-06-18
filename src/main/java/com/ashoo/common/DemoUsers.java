package com.ashoo.common;

import java.util.Set;

/**
 * Resolves the optional {@code user} request parameter to a safe, known user id.
 *
 * V1 is single-user, but the recruiter demo lets a viewer switch between the
 * default user and the three seeded personas to see how the same engine behaves
 * for different sensitivities. Centralizing the allow-list here means no endpoint
 * can be coaxed into reading an arbitrary user id from the query string — anything
 * unrecognized falls back to the default user. Environmental data always lives
 * under the default user in V1 (one shared Amsterdam history), so {@link #ENV_USER}
 * is constant regardless of which persona is being viewed.
 */
public final class DemoUsers {

    private DemoUsers() {}

    /** The single real user the app is built around. */
    public static final String DEFAULT_USER = "ashoo-user";

    /** Owner of the shared environmental snapshots in V1 (every persona reads these). */
    public static final String ENV_USER = "ashoo-user";

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

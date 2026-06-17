package com.ashoo.briefing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingDisclaimerInjector}.
 *
 * The safety-critical guarantee: the disclaimer is present at the end no matter what the
 * model returned — even if it omitted it, returned empty, or null.
 */
class BriefingDisclaimerInjectorTest {

    private final BriefingDisclaimerInjector injector = new BriefingDisclaimerInjector();

    @Test
    void appendsDisclaimerWhenModelOmitsIt() {
        String result = injector.injectDisclaimer("Conditions look elevated today.");
        assertThat(result).endsWith(BriefingDisclaimerInjector.DISCLAIMER);
        assertThat(result).startsWith("Conditions look elevated today.");
    }

    @Test
    void doesNotDuplicateWhenModelAlreadyIncludedIt() {
        String text = "Pollen is high. " + BriefingDisclaimerInjector.DISCLAIMER;
        String result = injector.injectDisclaimer(text);
        // The disclaimer should appear exactly once.
        int count = countOccurrences(result, BriefingDisclaimerInjector.DISCLAIMER);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void nullText_returnsJustTheDisclaimer() {
        assertThat(injector.injectDisclaimer(null)).isEqualTo(BriefingDisclaimerInjector.DISCLAIMER);
    }

    @Test
    void emptyText_returnsJustTheDisclaimer() {
        assertThat(injector.injectDisclaimer("   ")).isEqualTo(BriefingDisclaimerInjector.DISCLAIMER);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

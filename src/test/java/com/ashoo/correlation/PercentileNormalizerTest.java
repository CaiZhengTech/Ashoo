package com.ashoo.correlation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link PercentileNormalizer}.
 *
 * Covers the design's required edge cases (empty history, all-same values) plus
 * the mid-rank tie behavior that makes the normalizer robust.
 */
class PercentileNormalizerTest {

    private final PercentileNormalizer normalizer = new PercentileNormalizer();

    @Test
    void emptyHistory_returnsNeutralFifty() {
        assertThat(normalizer.normalize(42.0, List.of())).isEqualTo(50.0);
    }

    @Test
    void valueAboveAll_returns100() {
        assertThat(normalizer.normalize(100.0, List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(100.0);
    }

    @Test
    void valueBelowAll_returnsZero() {
        assertThat(normalizer.normalize(0.0, List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(0.0);
    }

    @Test
    void allSameValues_valueEqual_returnsFifty() {
        // Mid-rank splits the ties: equal to everything → exactly the middle.
        assertThat(normalizer.normalize(5.0, List.of(5.0, 5.0, 5.0, 5.0))).isEqualTo(50.0);
    }

    @Test
    void allSameValues_valueAbove_returns100() {
        assertThat(normalizer.normalize(6.0, List.of(5.0, 5.0, 5.0, 5.0))).isEqualTo(100.0);
    }

    @Test
    void median_returnsAboutFifty() {
        // 2 below, 2 above, none equal → exactly 50.
        assertThat(normalizer.normalize(3.0, List.of(1.0, 2.0, 4.0, 5.0)))
                .isCloseTo(50.0, within(0.001));
    }

    @Test
    void midRank_countsTiesAsHalf() {
        // value 3 against [1,3,3,5]: 1 below, 2 equal → (1 + 0.5*2)/4 * 100 = 50
        assertThat(normalizer.normalize(3.0, List.of(1.0, 3.0, 3.0, 5.0)))
                .isCloseTo(50.0, within(0.001));
    }
}

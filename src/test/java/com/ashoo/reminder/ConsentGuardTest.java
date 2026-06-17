package com.ashoo.reminder;

import com.ashoo.storage.entity.ConsentRecord;
import com.ashoo.storage.repository.ConsentRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConsentGuard}, the gate that protects every reminder and
 * medication feature.
 *
 * Verifies the gate blocks when no consent exists and opens once it does — the core
 * safety invariant the rest of the module relies on.
 */
class ConsentGuardTest {

    private final ConsentRecordRepository repo = mock(ConsentRecordRepository.class);
    private final ConsentGuard guard = new ConsentGuard(repo);

    @Test
    void requireConsent_throwsWhenNoConsentRecorded() {
        when(repo.findLatestByUserId("user")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireConsent("user"))
                .isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void requireConsent_passesWhenConsentExists() {
        when(repo.findLatestByUserId("user")).thenReturn(Optional.of(new ConsentRecord()));
        assertThatCode(() -> guard.requireConsent("user")).doesNotThrowAnyException();
    }

    @Test
    void hasConsent_reflectsRepository() {
        when(repo.findLatestByUserId("yes")).thenReturn(Optional.of(new ConsentRecord()));
        when(repo.findLatestByUserId("no")).thenReturn(Optional.empty());
        assertThat(guard.hasConsent("yes")).isTrue();
        assertThat(guard.hasConsent("no")).isFalse();
    }
}

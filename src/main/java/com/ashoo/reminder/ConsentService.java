package com.ashoo.reminder;

import com.ashoo.storage.entity.ConsentRecord;
import com.ashoo.storage.repository.ConsentRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Records and reports the user's acceptance of the advisory-only disclaimer.
 *
 * The exact disclaimer text is a compile-time constant here, never loaded from config
 * or a database. That is deliberate: the wording is a legal/safety statement, so changing
 * it must go through a code review, not a runtime toggle. We persist the text alongside
 * each acceptance so a stored consent always records precisely what was agreed to.
 */
@Service
public class ConsentService {

    /**
     * The advisory disclaimer the user accepts. Changing this is a code change by design.
     */
    public static final String CONSENT_DISCLAIMER =
            "I understand Ashoo is an informational wellness tool, not a medical device. "
            + "It does not diagnose, treat, or prescribe, and its reminders are my own notes "
            + "echoed back to me, not medical advice. I will always carry my prescribed "
            + "medication and consult my doctor for medical decisions.";

    private final ConsentRecordRepository consentRepo;

    public ConsentService(ConsentRecordRepository consentRepo) {
        this.consentRepo = consentRepo;
    }

    /**
     * Records the user's acceptance of the current disclaimer.
     *
     * @param userId the user identifier
     * @return the persisted consent record (including the exact text accepted)
     */
    public ConsentRecord recordConsent(String userId) {
        ConsentRecord record = new ConsentRecord();
        record.setUserId(userId);
        record.setConsentedAt(Instant.now());
        record.setDisclaimerText(CONSENT_DISCLAIMER);
        return consentRepo.save(record);
    }

    /**
     * Returns the user's most recent consent record, if they have consented.
     *
     * @param userId the user identifier
     * @return the latest consent, or empty if none
     */
    public Optional<ConsentRecord> getConsent(String userId) {
        return consentRepo.findLatestByUserId(userId);
    }
}

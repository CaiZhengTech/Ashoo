package com.ashoo.reminder;

import com.ashoo.storage.repository.ConsentRecordRepository;
import org.springframework.stereotype.Component;

/**
 * The single chokepoint that enforces "no medication or reminder feature without consent."
 *
 * Enforcement lives at the service layer (here), not the controller layer, on purpose:
 * a guard on the controller can be bypassed by any internal caller (a scheduler, a demo
 * seeder, a future endpoint). By making every reminder/medication service call through
 * this guard, there is exactly one rule and no way around it.
 */
@Component
public class ConsentGuard {

    private final ConsentRecordRepository consentRepo;

    public ConsentGuard(ConsentRecordRepository consentRepo) {
        this.consentRepo = consentRepo;
    }

    /**
     * Returns whether the user has ever accepted the disclaimer.
     *
     * @param userId the user identifier
     * @return true if a consent record exists
     */
    public boolean hasConsent(String userId) {
        return consentRepo.findLatestByUserId(userId).isPresent();
    }

    /**
     * Throws unless the user has accepted the disclaimer.
     *
     * @param userId the user identifier
     * @throws ConsentRequiredException if no consent record exists for the user
     */
    public void requireConsent(String userId) {
        if (!hasConsent(userId)) {
            throw new ConsentRequiredException();
        }
    }
}

package com.ashoo.storage.repository;

import com.ashoo.storage.entity.BriefingLog;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JDBC repository for the briefing audit log.
 *
 * The "today" endpoint reuses a same-day briefing instead of regenerating (and re-paying
 * for) one on every page load, so we expose a most-recent finder the service uses to
 * decide whether a cached briefing is still fresh.
 */
@Repository
public interface BriefingLogRepository extends CrudRepository<BriefingLog, Long> {

    /**
     * Returns the most recent briefing for a user, if any.
     *
     * @param userId the user identifier
     * @return the latest briefing log, or empty if none has been generated
     */
    @Query("SELECT * FROM briefing_log WHERE user_id = :userId ORDER BY generated_at DESC LIMIT 1")
    Optional<BriefingLog> findLatestByUserId(String userId);

    /**
     * Deletes all cached briefings for a user.
     *
     * Demo seeding calls this so the next dashboard load regenerates the briefing
     * against the freshly seeded data — otherwise the same-day cache would keep
     * serving a briefing written for the previous (now stale) risk numbers.
     *
     * @param userId the user whose cached briefings should be cleared
     */
    @Modifying
    @Query("DELETE FROM briefing_log WHERE user_id = :userId")
    void deleteByUserId(String userId);
}

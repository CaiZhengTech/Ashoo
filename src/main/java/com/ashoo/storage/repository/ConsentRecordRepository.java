package com.ashoo.storage.repository;

import com.ashoo.storage.entity.ConsentRecord;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JDBC repository for consent records.
 *
 * The gate check needs only "does the most recent consent exist for this user," so we
 * expose a single top-one finder. A user may consent more than once (e.g. after a
 * disclaimer wording change); the latest row is the one that governs access.
 */
@Repository
public interface ConsentRecordRepository extends CrudRepository<ConsentRecord, Long> {

    /**
     * Returns the most recent consent record for a user, if any.
     *
     * @param userId the user identifier
     * @return the latest consent, or empty if the user has never consented
     */
    @Query("SELECT * FROM consent_record WHERE user_id = :userId ORDER BY consented_at DESC LIMIT 1")
    Optional<ConsentRecord> findLatestByUserId(String userId);
}

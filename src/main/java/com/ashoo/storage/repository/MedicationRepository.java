package com.ashoo.storage.repository;

import com.ashoo.storage.entity.Medication;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JDBC repository for user-registered medications.
 *
 * CrudRepository fits because the table has a BIGSERIAL primary key. We expose an
 * active-only finder because removal is a soft delete — inactive rows must stay in
 * the table so old symptom-log references remain valid, but should never appear in lists.
 */
@Repository
public interface MedicationRepository extends CrudRepository<Medication, Long> {

    /**
     * Returns a user's active medications, newest first.
     *
     * @param userId the user identifier
     * @return active medications ordered by creation time descending
     */
    @Query("SELECT * FROM medication WHERE user_id = :userId AND is_active = TRUE ORDER BY created_at DESC")
    List<Medication> findActiveByUserId(String userId);
}

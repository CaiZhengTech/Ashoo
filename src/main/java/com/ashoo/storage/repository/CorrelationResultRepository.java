package com.ashoo.storage.repository;

import com.ashoo.storage.entity.CorrelationResult;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JDBC repository for cached correlation results.
 *
 * CrudRepository works here because {@code correlation_result} has a real
 * BIGSERIAL primary key. A correlation run replaces the whole result set for a
 * user, so we expose a bulk delete-by-user used as the first step of each
 * recompute — clear the stale rows, then insert the fresh ones.
 */
@Repository
public interface CorrelationResultRepository extends CrudRepository<CorrelationResult, Long> {

    /**
     * Returns the most recent correlation results for a user, strongest weight first.
     *
     * Ordering by weight descending means the dashboard's factor breakdown shows the
     * user's most influential triggers at the top without extra sorting.
     *
     * @param userId the user identifier
     * @return that user's correlation rows ordered by weight descending
     */
    @Query("SELECT * FROM correlation_result WHERE user_id = :userId ORDER BY weight DESC")
    List<CorrelationResult> findByUserId(String userId);

    /**
     * Deletes all correlation rows for a user.
     *
     * Called at the start of a recompute so the new run fully replaces the old one,
     * never leaving a stale factor row behind if a factor drops out of the model.
     *
     * @param userId the user identifier
     */
    @Modifying
    @Query("DELETE FROM correlation_result WHERE user_id = :userId")
    void deleteByUserId(String userId);
}

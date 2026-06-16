package com.ashoo.storage.repository;

import com.ashoo.storage.entity.SavedLocation;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JDBC repository for saved locations.
 *
 * CrudRepository works here because saved_location has a proper BIGSERIAL
 * primary key. Spring Data JDBC generates INSERT, UPDATE, DELETE, and
 * SELECT-by-id automatically — we only need to declare the custom queries.
 */
@Repository
public interface SavedLocationRepository extends CrudRepository<SavedLocation, Long> {

    /**
     * Returns all active saved locations for a user, newest first.
     *
     * @param userId the user identifier
     * @return active locations ordered by creation time descending
     */
    @Query("SELECT * FROM saved_location WHERE user_id = :userId AND is_active = TRUE ORDER BY created_at DESC")
    List<SavedLocation> findActiveByUserId(String userId);

    /**
     * Returns all active locations for a user (for pre-fetching environmental data).
     *
     * @param userId the user identifier
     * @return all active saved locations
     */
    @Query("SELECT * FROM saved_location WHERE user_id = :userId AND is_active = TRUE")
    List<SavedLocation> findAllActiveByUserId(String userId);

    /**
     * Clears the primary flag on all locations for a user before setting a new primary.
     *
     * Only one location should be marked primary at a time. Rather than querying
     * and updating each one individually, this single UPDATE is more efficient.
     *
     * @param userId the user identifier
     */
    @Modifying
    @Query("UPDATE saved_location SET is_primary = FALSE WHERE user_id = :userId")
    void clearPrimaryForUser(String userId);
}

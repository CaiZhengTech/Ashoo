package com.ashoo.storage.repository;

import com.ashoo.storage.entity.RecentSearch;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JDBC repository for recent ad-hoc location searches.
 *
 * The rolling-10 limit is enforced at the service layer: after each insert,
 * we delete any entries beyond the 10 most recent. This keeps the table small
 * without needing a database trigger.
 */
@Repository
public interface RecentSearchRepository extends CrudRepository<RecentSearch, Long> {

    /**
     * Returns the 10 most recent searches for a user, newest first.
     *
     * @param userId the user identifier
     * @return up to 10 recent searches
     */
    @Query("SELECT * FROM recent_search WHERE user_id = :userId ORDER BY searched_at DESC LIMIT 10")
    List<RecentSearch> findRecentByUserId(String userId);

    /**
     * Counts how many recent searches exist for a user.
     *
     * Used before inserting a new search to decide whether to prune old ones.
     *
     * @param userId the user identifier
     * @return total count of recent searches for this user
     */
    @Query("SELECT COUNT(*) FROM recent_search WHERE user_id = :userId")
    int countByUserId(String userId);

    /**
     * Deletes searches beyond the rolling 10 limit, keeping only the most recent ones.
     *
     * Uses a subquery to identify the IDs to keep, then deletes everything else.
     * This runs after every insert to maintain the rolling window.
     *
     * @param userId the user identifier
     */
    @Modifying
    @Query("""
           DELETE FROM recent_search
           WHERE user_id = :userId
           AND id NOT IN (
               SELECT id FROM recent_search
               WHERE user_id = :userId
               ORDER BY searched_at DESC
               LIMIT 10
           )
           """)
    void pruneToTen(String userId);
}

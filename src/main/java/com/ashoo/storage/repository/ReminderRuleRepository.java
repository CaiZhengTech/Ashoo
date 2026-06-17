package com.ashoo.storage.repository;

import com.ashoo.storage.entity.ReminderRule;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JDBC repository for user-defined reminder rules.
 *
 * CrudRepository fits the BIGSERIAL primary key. The active-only finder is what the
 * reminder engine evaluates each time it scores risk.
 */
@Repository
public interface ReminderRuleRepository extends CrudRepository<ReminderRule, Long> {

    /**
     * Returns a user's active reminder rules.
     *
     * @param userId the user identifier
     * @return active rules ordered by threshold ascending (lowest trips first)
     */
    @Query("SELECT * FROM reminder_rule WHERE user_id = :userId AND is_active = TRUE ORDER BY risk_score_threshold ASC")
    List<ReminderRule> findActiveByUserId(String userId);
}

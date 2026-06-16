package com.ashoo.storage.repository;

import com.ashoo.storage.entity.RecalibrationEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for recalibration events.
 *
 * These are append-only audit records — we only ever insert, never update or delete.
 * CrudRepository's save() method is all we need here.
 */
@Repository
public interface RecalibrationEventRepository extends CrudRepository<RecalibrationEvent, Long> {}

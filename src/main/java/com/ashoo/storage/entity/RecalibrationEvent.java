package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Records each time a symptom log edit triggers a correlation recomputation.
 *
 * Stored for transparency — the user can see that their edit caused the
 * model to update and how long it took. This is part of Ashoo's commitment
 * to showing the user how the engine works rather than hiding it.
 */
@Table("recalibration_event")
public class RecalibrationEvent {

    @Id
    private Long id;
    private String userId;
    private Instant triggeredAt;
    private String reason;
    private Long symptomLogId;
    private Integer recomputationMs;

    public RecalibrationEvent() {}

    public Long    getId()               { return id; }
    public String  getUserId()           { return userId; }
    public Instant getTriggeredAt()      { return triggeredAt; }
    public String  getReason()           { return reason; }
    public Long    getSymptomLogId()     { return symptomLogId; }
    public Integer getRecomputationMs()  { return recomputationMs; }

    public void setId(Long id)                  { this.id = id; }
    public void setUserId(String v)             { this.userId = v; }
    public void setTriggeredAt(Instant v)       { this.triggeredAt = v; }
    public void setReason(String v)             { this.reason = v; }
    public void setSymptomLogId(Long v)         { this.symptomLogId = v; }
    public void setRecomputationMs(Integer v)   { this.recomputationMs = v; }
}

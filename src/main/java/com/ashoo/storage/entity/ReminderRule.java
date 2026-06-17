package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalTime;

/**
 * A user-defined rule: "when my risk score crosses this threshold, show me this note."
 *
 * The {@code userNote} is written entirely by the user — the engine only evaluates the
 * condition and echoes the note back. The mandatory disclaimer is appended at evaluation
 * time by the reminder engine and is deliberately NOT stored here, so it can never be
 * edited out of a saved rule.
 *
 * The optional time window ({@code timeWindowStart}/{@code timeWindowEnd}) suppresses
 * alerts outside hours the user can act on — e.g. no 11pm pings. Spring Data JDBC maps
 * the SQL {@code TIME} columns to {@link LocalTime}.
 */
@Table("reminder_rule")
public class ReminderRule {

    @Id
    private Long id;
    private String userId;
    private Double riskScoreThreshold;
    private String userNote;
    private Long medicationId;
    private LocalTime timeWindowStart;
    private LocalTime timeWindowEnd;
    private Boolean isActive;
    private Instant createdAt;

    public ReminderRule() {}

    public Long      getId()                 { return id; }
    public String    getUserId()             { return userId; }
    public Double    getRiskScoreThreshold() { return riskScoreThreshold; }
    public String    getUserNote()           { return userNote; }
    public Long      getMedicationId()       { return medicationId; }
    public LocalTime getTimeWindowStart()    { return timeWindowStart; }
    public LocalTime getTimeWindowEnd()      { return timeWindowEnd; }
    public Boolean   getIsActive()           { return isActive; }
    public Instant   getCreatedAt()          { return createdAt; }

    public void setId(Long v)                  { this.id = v; }
    public void setUserId(String v)            { this.userId = v; }
    public void setRiskScoreThreshold(Double v){ this.riskScoreThreshold = v; }
    public void setUserNote(String v)          { this.userNote = v; }
    public void setMedicationId(Long v)        { this.medicationId = v; }
    public void setTimeWindowStart(LocalTime v){ this.timeWindowStart = v; }
    public void setTimeWindowEnd(LocalTime v)  { this.timeWindowEnd = v; }
    public void setIsActive(Boolean v)         { this.isActive = v; }
    public void setCreatedAt(Instant v)        { this.createdAt = v; }
}

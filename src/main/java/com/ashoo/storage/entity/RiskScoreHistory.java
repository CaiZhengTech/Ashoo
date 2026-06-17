package com.ashoo.storage.entity;

import java.time.Instant;

/**
 * One computed Personal Risk Index reading, written once per hourly scoring run.
 *
 * Maps to the {@code risk_score_history} hypertable. Like the other time-series
 * tables, it has no primary key (TimescaleDB partitions by {@code scored_at}), so
 * it is a plain class persisted via JdbcTemplate rather than a Spring Data entity.
 *
 * {@code factorScores} holds the per-factor normalized contributions as a JSON
 * string. Storing it as JSONB (not separate columns) lets new factors appear in
 * the breakdown without a schema migration — the chart just reads whatever keys
 * are present.
 */
public class RiskScoreHistory {

    private Instant scoredAt;
    private String userId;
    private Double riskScore;
    private Double riskScoreSmoothed;
    private String riskLabel;
    private Boolean alertTriggered;
    private String factorScores; // JSON object: {"pm25": 72.3, "pollen_grass": 88.1}
    private String confidenceLevel;
    private Integer symptomDaysAvailable;

    public RiskScoreHistory() {}

    public Instant getScoredAt()             { return scoredAt; }
    public String  getUserId()               { return userId; }
    public Double  getRiskScore()            { return riskScore; }
    public Double  getRiskScoreSmoothed()    { return riskScoreSmoothed; }
    public String  getRiskLabel()            { return riskLabel; }
    public Boolean getAlertTriggered()       { return alertTriggered; }
    public String  getFactorScores()         { return factorScores; }
    public String  getConfidenceLevel()      { return confidenceLevel; }
    public Integer getSymptomDaysAvailable() { return symptomDaysAvailable; }

    public void setScoredAt(Instant v)             { this.scoredAt = v; }
    public void setUserId(String v)                { this.userId = v; }
    public void setRiskScore(Double v)             { this.riskScore = v; }
    public void setRiskScoreSmoothed(Double v)     { this.riskScoreSmoothed = v; }
    public void setRiskLabel(String v)             { this.riskLabel = v; }
    public void setAlertTriggered(Boolean v)       { this.alertTriggered = v; }
    public void setFactorScores(String v)          { this.factorScores = v; }
    public void setConfidenceLevel(String v)       { this.confidenceLevel = v; }
    public void setSymptomDaysAvailable(Integer v) { this.symptomDaysAvailable = v; }
}

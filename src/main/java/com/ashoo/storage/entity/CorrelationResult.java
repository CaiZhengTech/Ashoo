package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One factor's learned relationship to a user's symptoms — the cached output of
 * a correlation run.
 *
 * One row per factor per user. These rows are small, high-value, and kept
 * indefinitely (recomputed, not appended), so a regular table with a BIGSERIAL
 * key fits perfectly — which is why this uses Spring Data JDBC's CrudRepository
 * rather than the JdbcTemplate approach the hypertables need.
 *
 * The fields mirror the {@code correlation_result} columns from migration V7;
 * Spring Data JDBC maps snake_case columns to these camelCase fields automatically.
 */
@Table("correlation_result")
public class CorrelationResult {

    @Id
    private Long id;
    private String userId;
    private Instant computedAt;
    private String factorName;
    private Integer bestLagHours;
    private Double spearmanR;
    private Double pointBiserialR;
    private Double personalThreshold;
    private Double thresholdPercentile;
    private Double weight;
    private String confidenceLevel;
    private Integer symptomDaysUsed;
    private Integer totalDaysUsed;
    private Integer mismatchCount;

    public CorrelationResult() {}

    public Long    getId()                  { return id; }
    public String  getUserId()              { return userId; }
    public Instant getComputedAt()          { return computedAt; }
    public String  getFactorName()          { return factorName; }
    public Integer getBestLagHours()        { return bestLagHours; }
    public Double  getSpearmanR()           { return spearmanR; }
    public Double  getPointBiserialR()      { return pointBiserialR; }
    public Double  getPersonalThreshold()   { return personalThreshold; }
    public Double  getThresholdPercentile() { return thresholdPercentile; }
    public Double  getWeight()              { return weight; }
    public String  getConfidenceLevel()     { return confidenceLevel; }
    public Integer getSymptomDaysUsed()     { return symptomDaysUsed; }
    public Integer getTotalDaysUsed()       { return totalDaysUsed; }
    public Integer getMismatchCount()       { return mismatchCount; }

    public void setId(Long v)                  { this.id = v; }
    public void setUserId(String v)            { this.userId = v; }
    public void setComputedAt(Instant v)       { this.computedAt = v; }
    public void setFactorName(String v)        { this.factorName = v; }
    public void setBestLagHours(Integer v)     { this.bestLagHours = v; }
    public void setSpearmanR(Double v)         { this.spearmanR = v; }
    public void setPointBiserialR(Double v)    { this.pointBiserialR = v; }
    public void setPersonalThreshold(Double v) { this.personalThreshold = v; }
    public void setThresholdPercentile(Double v) { this.thresholdPercentile = v; }
    public void setWeight(Double v)            { this.weight = v; }
    public void setConfidenceLevel(String v)   { this.confidenceLevel = v; }
    public void setSymptomDaysUsed(Integer v)  { this.symptomDaysUsed = v; }
    public void setTotalDaysUsed(Integer v)    { this.totalDaysUsed = v; }
    public void setMismatchCount(Integer v)    { this.mismatchCount = v; }
}

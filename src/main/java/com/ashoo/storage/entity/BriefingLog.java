package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * An audit record of one generated daily briefing.
 *
 * Stores the full text shown to the user plus token usage, so we can audit what the AI
 * said, debug fallbacks, and track cost. Demo briefings are flagged ({@code isDemo}) so
 * they can be filtered out of real-usage accounting. Uses Spring Data JDBC because the
 * table has a BIGSERIAL primary key.
 */
@Table("briefing_log")
public class BriefingLog {

    @Id
    private Long id;
    private String userId;
    private Instant generatedAt;
    private Double riskScore;
    private String riskLabel;
    private String briefingText;
    private Integer tokensUsed;
    private Boolean isDemo;

    public BriefingLog() {}

    public Long    getId()           { return id; }
    public String  getUserId()       { return userId; }
    public Instant getGeneratedAt()  { return generatedAt; }
    public Double  getRiskScore()    { return riskScore; }
    public String  getRiskLabel()    { return riskLabel; }
    public String  getBriefingText() { return briefingText; }
    public Integer getTokensUsed()   { return tokensUsed; }
    public Boolean getIsDemo()       { return isDemo; }

    public void setId(Long v)            { this.id = v; }
    public void setUserId(String v)      { this.userId = v; }
    public void setGeneratedAt(Instant v){ this.generatedAt = v; }
    public void setRiskScore(Double v)   { this.riskScore = v; }
    public void setRiskLabel(String v)   { this.riskLabel = v; }
    public void setBriefingText(String v){ this.briefingText = v; }
    public void setTokensUsed(Integer v) { this.tokensUsed = v; }
    public void setIsDemo(Boolean v)     { this.isDemo = v; }
}

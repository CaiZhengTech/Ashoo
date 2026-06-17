package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A timestamped record that the user explicitly accepted the advisory-only disclaimer.
 *
 * No reminder or medication feature is reachable until such a record exists — this is
 * how Ashoo implements the FDA General Wellness guidance requirement to make clear it is
 * not a medical device. We store the <em>exact</em> disclaimer text accepted so the record
 * stays meaningful even if the wording changes in a future version: we always know what
 * the user actually agreed to.
 */
@Table("consent_record")
public class ConsentRecord {

    @Id
    private Long id;
    private String userId;
    private Instant consentedAt;
    private String disclaimerText;

    public ConsentRecord() {}

    public Long    getId()             { return id; }
    public String  getUserId()         { return userId; }
    public Instant getConsentedAt()    { return consentedAt; }
    public String  getDisclaimerText() { return disclaimerText; }

    public void setId(Long v)              { this.id = v; }
    public void setUserId(String v)        { this.userId = v; }
    public void setConsentedAt(Instant v)  { this.consentedAt = v; }
    public void setDisclaimerText(String v){ this.disclaimerText = v; }
}

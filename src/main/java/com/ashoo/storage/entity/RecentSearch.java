package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * An ad-hoc location lookup that the user made but did not save permanently.
 *
 * Stored for quick re-access (rolling last 10 per user). Application logic
 * enforces the 10-row limit on insert by deleting the oldest entry when
 * the count exceeds 10 — simpler than a database trigger for this use case.
 */
@Table("recent_search")
public class RecentSearch {

    @Id
    private Long id;
    private String userId;
    private String cityName;
    private Double latitude;
    private Double longitude;
    private Instant searchedAt;

    public RecentSearch() {}

    public Long    getId()         { return id; }
    public String  getUserId()     { return userId; }
    public String  getCityName()   { return cityName; }
    public Double  getLatitude()   { return latitude; }
    public Double  getLongitude()  { return longitude; }
    public Instant getSearchedAt() { return searchedAt; }

    public void setId(Long id)              { this.id = id; }
    public void setUserId(String v)         { this.userId = v; }
    public void setCityName(String v)       { this.cityName = v; }
    public void setLatitude(Double v)       { this.latitude = v; }
    public void setLongitude(Double v)      { this.longitude = v; }
    public void setSearchedAt(Instant v)    { this.searchedAt = v; }
}

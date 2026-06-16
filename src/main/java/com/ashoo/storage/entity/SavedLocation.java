package com.ashoo.storage.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A user's pre-registered named location (Home, Work, etc.).
 *
 * Using Spring Data JDBC here (unlike EnvironmentalSnapshot) because this table
 * has a proper primary key — JDBC's CrudRepository requires @Id to manage entities.
 * Spring Data JDBC maps snake_case column names to camelCase fields automatically.
 */
@Table("saved_location")
public class SavedLocation {

    @Id
    private Long id;
    private String userId;
    private String label;
    private String cityName;
    private String county;
    private String country;
    private Double latitude;
    private Double longitude;
    private Boolean isPrimary;
    private Boolean isActive;
    private Instant createdAt;

    public SavedLocation() {}

    public Long    getId()        { return id; }
    public String  getUserId()    { return userId; }
    public String  getLabel()     { return label; }
    public String  getCityName()  { return cityName; }
    public String  getCounty()    { return county; }
    public String  getCountry()   { return country; }
    public Double  getLatitude()  { return latitude; }
    public Double  getLongitude() { return longitude; }
    public Boolean getIsPrimary() { return isPrimary; }
    public Boolean getIsActive()  { return isActive; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id)              { this.id = id; }
    public void setUserId(String userId)    { this.userId = userId; }
    public void setLabel(String label)      { this.label = label; }
    public void setCityName(String v)       { this.cityName = v; }
    public void setCounty(String v)         { this.county = v; }
    public void setCountry(String v)        { this.country = v; }
    public void setLatitude(Double v)       { this.latitude = v; }
    public void setLongitude(Double v)      { this.longitude = v; }
    public void setIsPrimary(Boolean v)     { this.isPrimary = v; }
    public void setIsActive(Boolean v)      { this.isActive = v; }
    public void setCreatedAt(Instant v)     { this.createdAt = v; }
}

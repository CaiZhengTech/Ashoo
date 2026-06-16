package com.ashoo.storage.entity;

import java.time.Instant;

/**
 * A single user-logged symptom entry — the label data for the correlation engine.
 *
 * Maps to the {@code symptom_log} TimescaleDB hypertable. Like EnvironmentalSnapshot,
 * we use JdbcTemplate directly rather than Spring Data JDBC's CrudRepository because
 * hypertables require careful handling of the time partition column.
 *
 * The {@code medicationsUsed} field is a Postgres {@code BIGINT[]} array. When
 * persisting, we convert it to a {@code java.sql.Array} using the JDBC connection.
 */
public class SymptomLog {

    private Long id;
    private Instant loggedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String userId;
    private Integer severity;
    private String notes;
    private Long locationId;
    private String cityName;
    private Long[] medicationsUsed;
    private String dataOrigin;

    public SymptomLog() {}

    public Long     getId()              { return id; }
    public Instant  getLoggedAt()        { return loggedAt; }
    public Instant  getCreatedAt()       { return createdAt; }
    public Instant  getUpdatedAt()       { return updatedAt; }
    public String   getUserId()          { return userId; }
    public Integer  getSeverity()        { return severity; }
    public String   getNotes()           { return notes; }
    public Long     getLocationId()      { return locationId; }
    public String   getCityName()        { return cityName; }
    public Long[]   getMedicationsUsed() { return medicationsUsed; }
    public String   getDataOrigin()      { return dataOrigin; }

    public void setId(Long id)                      { this.id = id; }
    public void setLoggedAt(Instant v)              { this.loggedAt = v; }
    public void setCreatedAt(Instant v)             { this.createdAt = v; }
    public void setUpdatedAt(Instant v)             { this.updatedAt = v; }
    public void setUserId(String v)                 { this.userId = v; }
    public void setSeverity(Integer v)              { this.severity = v; }
    public void setNotes(String v)                  { this.notes = v; }
    public void setLocationId(Long v)               { this.locationId = v; }
    public void setCityName(String v)               { this.cityName = v; }
    public void setMedicationsUsed(Long[] v)        { this.medicationsUsed = v; }
    public void setDataOrigin(String v)             { this.dataOrigin = v; }
}

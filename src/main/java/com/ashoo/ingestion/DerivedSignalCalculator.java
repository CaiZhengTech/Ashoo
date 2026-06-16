package com.ashoo.ingestion;

import com.ashoo.common.AqiCalculator;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Computes derived signals from a raw environmental reading and its predecessor.
 *
 * Derived signals are computed once at ingest time and stored, rather than
 * recomputed on every query. This trades a small amount of storage for faster
 * query performance when the correlation engine scans months of history.
 *
 * The thunderstorm flag is a HEURISTIC based on meteorological conditions
 * associated with thunderstorm-asthma events (high grass pollen + strong
 * wind gusts + pressure drop). It is not a validated weather prediction.
 */
@Component
public class DerivedSignalCalculator {

    /**
     * Populates derived fields on a snapshot by comparing it with the previous reading.
     *
     * If no previous reading exists (first reading for this location), deltas
     * default to null — the UI and correlation engine handle nulls gracefully
     * rather than displaying misleading zeros.
     *
     * @param current  the freshly ingested snapshot (modified in place)
     * @param previous the prior reading for this location, or empty if first reading
     */
    public void compute(EnvironmentalSnapshot current, Optional<EnvironmentalSnapshot> previous) {
        Double rateOfChange = null;
        Double pressureDrop = null;
        Double cumulative24h = null;

        if (previous.isPresent()) {
            EnvironmentalSnapshot prev = previous.get();

            rateOfChange = computeRateOfChange(current.getPm25(), prev.getPm25());
            pressureDrop = computePressureDrop(current.getPressureMslHpa(), prev.getPressureMslHpa());
            cumulative24h = current.getPm25();
        }

        Integer aqi = AqiCalculator.computeFromPm25(current.getPm25());
        if (aqi == -1) aqi = null;

        Boolean thunderstorm = computeThunderstormFlag(
                current.getPollenGrass(), current.getWindGustsMs(), pressureDrop);

        current.setDerivedSignals(rateOfChange, pressureDrop, cumulative24h, aqi, thunderstorm);
    }

    /**
     * Computes the hourly rate of change of PM2.5 for spike detection.
     *
     * A large positive delta indicates a rapidly worsening air quality event.
     * The correlation engine can test whether spikes (not just absolute levels)
     * predict the user's symptoms.
     *
     * @param current  current PM2.5 reading
     * @param previous previous PM2.5 reading
     * @return delta (current - previous), or null if either reading is missing
     */
    Double computeRateOfChange(Double current, Double previous) {
        if (current == null || previous == null) return null;
        return current - previous;
    }

    /**
     * Computes the barometric pressure change between consecutive readings.
     *
     * A negative value (pressure dropping) often precedes storm fronts.
     * Some asthma patients report symptom flares correlated with rapid
     * pressure drops, though the mechanism is not well established.
     *
     * @param current  current pressure in hPa
     * @param previous previous pressure in hPa
     * @return pressure delta (current - previous), or null if either is missing
     */
    Double computePressureDrop(Double current, Double previous) {
        if (current == null || previous == null) return null;
        return current - previous;
    }

    /**
     * Heuristic thunderstorm-asthma flag based on three simultaneous conditions:
     * high grass pollen + strong wind gusts + falling pressure.
     *
     * Thunderstorm-asthma is a documented phenomenon where storm downdrafts
     * rupture pollen grains into respirable fragments. This flag is NOT a
     * weather forecast — it signals that current conditions resemble those
     * associated with past events. Must be labeled clearly in any UI.
     *
     * @param grassPollen  current grass pollen level (grains/m³)
     * @param windGusts    current wind gust speed (m/s)
     * @param pressureDrop pressure change from previous reading (hPa)
     * @return true if all three conditions are met, false otherwise
     */
    Boolean computeThunderstormFlag(Double grassPollen, Double windGusts, Double pressureDrop) {
        if (grassPollen == null || windGusts == null || pressureDrop == null) {
            return false;
        }
        return grassPollen > 30.0 && windGusts > 10.0 && pressureDrop < -2.0;
    }
}

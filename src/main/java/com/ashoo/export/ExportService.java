package com.ashoo.export;

import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Builds a CSV export joining the user's symptom log with daily environmental conditions.
 *
 * The output is a flat, one-row-per-day table — the shape a user can open in Excel or
 * feed to their own analysis. This makes Ashoo's promise concrete: it is the user's data,
 * portable at any time. Daily granularity (rather than raw hourly) matches how symptoms
 * are logged and keeps the file small and legible.
 */
@Service
public class ExportService {

    /** Open-Meteo attribution required on every response that surfaces their data. */
    public static final String ATTRIBUTION =
            "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)";

    private static final String HEADER = String.join(",",
            "date", "severity", "notes", "location", "medications_used",
            "avg_pm25", "avg_pm10", "avg_o3", "avg_pollen_grass",
            "avg_humidity_pct", "avg_pressure_msl_hpa", "avg_temperature_c", "avg_aqi",
            "data_origin");

    private final SymptomLogRepository symptomRepo;
    private final EnvironmentalSnapshotRepository snapshotRepo;

    public ExportService(SymptomLogRepository symptomRepo,
                         EnvironmentalSnapshotRepository snapshotRepo) {
        this.symptomRepo = symptomRepo;
        this.snapshotRepo = snapshotRepo;
    }

    /**
     * Produces the CSV text for a user's data over a date range.
     *
     * Every day in the range that has either a symptom log or environmental data becomes a
     * row; days with neither are omitted. Symptom fields come from that day's logged entry
     * (worst severity wins if multiple), and the environmental columns are daily averages.
     *
     * @param userId the user whose data to export
     * @param from   inclusive start
     * @param to     inclusive end
     * @return the full CSV document as a string, ending with an attribution comment line
     */
    public String exportCsv(String userId, Instant from, Instant to) {
        List<SymptomLog> symptoms = symptomRepo.findByDateRange(userId, from, to);
        List<EnvironmentalSnapshot> snapshots = snapshotRepo.findByDateRange(userId, from, to);

        Map<LocalDate, DailySymptom> symptomByDay = aggregateSymptoms(symptoms);
        Map<LocalDate, DailyEnv> envByDay = aggregateEnv(snapshots);

        TreeSet<LocalDate> days = new TreeSet<>();
        days.addAll(symptomByDay.keySet());
        days.addAll(envByDay.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\n");
        for (LocalDate day : days) {
            sb.append(buildRow(day, symptomByDay.get(day), envByDay.get(day))).append("\n");
        }
        // Trailing comment line carries the mandatory attribution without breaking the data rows.
        sb.append("# ").append(ATTRIBUTION).append("\n");
        return sb.toString();
    }

    private String buildRow(LocalDate day, DailySymptom symptom, DailyEnv env) {
        List<String> cols = new ArrayList<>();
        cols.add(day.toString());
        cols.add(symptom != null ? String.valueOf(symptom.maxSeverity) : "");
        cols.add(symptom != null ? escape(symptom.notes) : "");
        cols.add(symptom != null ? escape(symptom.location) : "");
        cols.add(symptom != null ? escape(symptom.medications) : "");
        cols.add(env != null ? num(env.avg("pm25")) : "");
        cols.add(env != null ? num(env.avg("pm10")) : "");
        cols.add(env != null ? num(env.avg("o3")) : "");
        cols.add(env != null ? num(env.avg("pollen_grass")) : "");
        cols.add(env != null ? num(env.avg("humidity_pct")) : "");
        cols.add(env != null ? num(env.avg("pressure_msl_hpa")) : "");
        cols.add(env != null ? num(env.avg("temperature_c")) : "");
        cols.add(env != null ? num(env.avg("aqi")) : "");
        cols.add(resolveOrigin(symptom, env));
        return String.join(",", cols);
    }

    private Map<LocalDate, DailySymptom> aggregateSymptoms(List<SymptomLog> symptoms) {
        Map<LocalDate, DailySymptom> byDay = new HashMap<>();
        for (SymptomLog s : symptoms) {
            LocalDate day = s.getLoggedAt().atZone(ZoneOffset.UTC).toLocalDate();
            DailySymptom d = byDay.computeIfAbsent(day, k -> new DailySymptom());
            int severity = s.getSeverity() != null ? s.getSeverity() : 0;
            if (severity >= d.maxSeverity) {
                d.maxSeverity = severity;
                if (s.getNotes() != null && !s.getNotes().isBlank()) d.notes = s.getNotes();
                if (s.getCityName() != null) d.location = s.getCityName();
            }
            if (s.getMedicationsUsed() != null && s.getMedicationsUsed().length > 0) {
                StringJoiner sj = new StringJoiner(";");
                for (Long id : s.getMedicationsUsed()) sj.add(String.valueOf(id));
                d.medications = sj.toString();
            }
            if (s.getDataOrigin() != null) d.dataOrigin = s.getDataOrigin();
        }
        return byDay;
    }

    private Map<LocalDate, DailyEnv> aggregateEnv(List<EnvironmentalSnapshot> snapshots) {
        Map<LocalDate, DailyEnv> byDay = new HashMap<>();
        for (EnvironmentalSnapshot s : snapshots) {
            LocalDate day = s.getRecordedAt().atZone(ZoneOffset.UTC).toLocalDate();
            DailyEnv d = byDay.computeIfAbsent(day, k -> new DailyEnv());
            d.add("pm25", s.getPm25());
            d.add("pm10", s.getPm10());
            d.add("o3", s.getO3());
            d.add("pollen_grass", s.getPollenGrass());
            d.add("humidity_pct", s.getHumidityPct());
            d.add("pressure_msl_hpa", s.getPressureMslHpa());
            d.add("temperature_c", s.getTemperatureC());
            d.add("aqi", s.getAqiComputed() != null ? s.getAqiComputed().doubleValue() : null);
            if (s.getDataOrigin() != null) d.dataOrigin = s.getDataOrigin();
        }
        return byDay;
    }

    private static String resolveOrigin(DailySymptom symptom, DailyEnv env) {
        if (symptom != null && symptom.dataOrigin != null) return symptom.dataOrigin;
        if (env != null && env.dataOrigin != null) return env.dataOrigin;
        return "";
    }

    private static String num(Double v) {
        return v == null ? "" : String.format(Locale.US, "%.1f", v);
    }

    /**
     * Escapes a CSV field per RFC 4180: wrap in quotes and double any inner quotes when the
     * value contains a comma, quote, or newline.
     *
     * @param value the raw field value (may be null)
     * @return the safely-quoted field, or empty string for null
     */
    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Mutable accumulator for one day's symptom fields. */
    private static final class DailySymptom {
        int maxSeverity = 0;
        String notes = "";
        String location = "";
        String medications = "";
        String dataOrigin;
    }

    /** Mutable accumulator for one day's environmental averages. */
    private static final class DailyEnv {
        private final Map<String, double[]> sums = new HashMap<>(); // factor -> [sum, count]
        String dataOrigin;

        void add(String factor, Double value) {
            if (value == null) return;
            double[] sc = sums.computeIfAbsent(factor, k -> new double[2]);
            sc[0] += value;
            sc[1] += 1;
        }

        Double avg(String factor) {
            double[] sc = sums.get(factor);
            return (sc == null || sc[1] == 0) ? null : sc[0] / sc[1];
        }
    }
}

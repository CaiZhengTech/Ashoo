package com.ashoo;

import com.ashoo.export.ExportService;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the M6 CSV export, against a real TimescaleDB.
 *
 * Seeds a few days of environmental data plus a symptom log (including a note containing a
 * comma, to exercise CSV escaping), calls the export, parses the result, and verifies the
 * row count, the joined values, the escaping, and the mandatory attribution line.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class M6ExportTest {

    private static final String USER = "m6-export-user";

    @Autowired private ExportService exportService;
    @Autowired private EnvironmentalSnapshotRepository snapshotRepo;
    @Autowired private SymptomLogRepository symptomRepo;

    @Test
    void export_joinsSymptomsAndConditions_andEscapesCsv() {
        LocalDate baseDay = LocalDate.of(2026, 3, 10);
        Instant base = baseDay.atStartOfDay(ZoneOffset.UTC).toInstant();

        // 3 days of environmental data
        for (int d = 0; d < 3; d++) {
            snapshotRepo.save(EnvironmentalSnapshot.builder()
                    .recordedAt(baseDay.plusDays(d).atTime(12, 0).toInstant(ZoneOffset.UTC))
                    .userId(USER).cityName("Sharon, MA")
                    .pm25(10.0 + d).humidityPct(50.0 + d).aqiComputed(40 + d)
                    .dataSource("TEST").dataOrigin("REAL")
                    .build());
        }

        // A symptom on the middle day, with a comma in the note (forces quoting)
        SymptomLog log = new SymptomLog();
        log.setUserId(USER);
        log.setLoggedAt(baseDay.plusDays(1).atTime(8, 0).toInstant(ZoneOffset.UTC));
        log.setSeverity(6);
        log.setNotes("Itchy eyes, runny nose");
        log.setCityName("Sharon, MA");
        symptomRepo.save(log);

        Instant from = base.minusSeconds(86400);
        Instant to = baseDay.plusDays(4).atStartOfDay(ZoneOffset.UTC).toInstant();
        String csv = exportService.exportCsv(USER, from, to);

        List<String> lines = Arrays.stream(csv.split("\n"))
                .filter(l -> !l.isBlank())
                .toList();

        // Header + 3 data rows + 1 attribution comment line
        String header = lines.getFirst();
        assertThat(header).startsWith("date,severity,notes,location");

        List<String> dataRows = lines.stream()
                .filter(l -> !l.startsWith("date") && !l.startsWith("#"))
                .toList();
        assertThat(dataRows).hasSize(3); // 3 distinct days had env data

        // The middle day should carry the symptom severity and the quoted note.
        String middleRow = dataRows.stream()
                .filter(l -> l.startsWith("2026-03-11"))
                .findFirst().orElseThrow();
        assertThat(middleRow).contains(",6,");
        assertThat(middleRow).contains("\"Itchy eyes, runny nose\""); // comma forced quoting

        // Attribution must be present.
        assertThat(csv).contains(ExportService.ATTRIBUTION);
    }

    @Test
    void export_emptyRange_returnsHeaderAndAttributionOnly() {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-01-02T00:00:00Z");
        String csv = exportService.exportCsv(USER, from, to);

        List<String> dataRows = Arrays.stream(csv.split("\n"))
                .filter(l -> !l.isBlank() && !l.startsWith("date") && !l.startsWith("#"))
                .toList();
        assertThat(dataRows).isEmpty();
        assertThat(csv).contains(ExportService.ATTRIBUTION);
    }
}

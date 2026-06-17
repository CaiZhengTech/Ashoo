package com.ashoo.api;

import com.ashoo.export.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * CSV export of the user's symptom log joined with daily environmental conditions.
 *
 * Returns a downloadable file (Content-Disposition: attachment) so a browser hitting the
 * URL saves it directly. This is Ashoo's data-portability guarantee — the user can take
 * their full history at any time.
 */
@RestController
@RequestMapping("/api/v1/export")
public class ExportController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * Exports the user's data in the given date range as CSV.
     *
     * @param from inclusive start (ISO-8601)
     * @param to   inclusive end (ISO-8601)
     * @return a text/csv attachment
     */
    @GetMapping
    public ResponseEntity<String> export(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        String csv = exportService.exportCsv(DEFAULT_USER, from, to);
        String filename = String.format("ashoo-export-%s-to-%s.csv",
                from.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                to.atZone(java.time.ZoneOffset.UTC).toLocalDate());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Data-Attribution", ExportService.ATTRIBUTION)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}

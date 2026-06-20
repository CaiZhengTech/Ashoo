package com.ashoo.api;

import com.ashoo.api.dto.ReminderRuleRequest;
import com.ashoo.api.dto.ReminderRuleResponse;
import com.ashoo.common.DemoUsers;
import com.ashoo.reminder.ReminderRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * CRUD for the user's reminder rules.
 *
 * Consent-gated in the service layer. The controller validates the threshold range; the
 * note and time window are stored verbatim. The mandatory disclaimer is never stored here
 * — it is appended only when a rule fires.
 */
@RestController
@RequestMapping("/api/v1/reminder-rules")
public class ReminderRuleController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final ReminderRuleService ruleService;

    public ReminderRuleController(ReminderRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * Creates a reminder rule.
     *
     * @param req the rule details (threshold must be 0–100, note required)
     * @return the created rule (201), or 400 on validation failure
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody ReminderRuleRequest req) {
        if (req.riskScoreThreshold() == null
                || req.riskScoreThreshold() < 0 || req.riskScoreThreshold() > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "riskScoreThreshold must be between 0 and 100"));
        }
        if (req.userNote() == null || req.userNote().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userNote is required"));
        }
        var saved = ruleService.create(DEFAULT_USER, req.riskScoreThreshold(),
                req.userNote().trim(), req.medicationId(),
                req.timeWindowStart(), req.timeWindowEnd());
        return ResponseEntity
                .created(URI.create("/api/v1/reminder-rules/" + saved.getId()))
                .body(ReminderRuleResponse.from(saved));
    }

    /**
     * Lists active reminder rules for the given user (or the default user when omitted).
     *
     * The optional {@code user} param lets the dashboard show a viewed demo persona's
     * own pre-configured rules; creating and deleting still act on the real user only.
     *
     * @param user optional persona to view (default user when omitted/unknown)
     * @return active rules
     */
    @GetMapping
    public List<ReminderRuleResponse> list(@RequestParam(required = false) String user) {
        return ruleService.list(DemoUsers.resolve(user)).stream()
                .map(ReminderRuleResponse::from)
                .toList();
    }

    /**
     * Soft-deletes a reminder rule.
     *
     * @param id the rule id
     * @return 204 if removed, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        return ruleService.remove(DEFAULT_USER, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}

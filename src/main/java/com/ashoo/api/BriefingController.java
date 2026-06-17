package com.ashoo.api;

import com.ashoo.api.dto.BriefingResponse;
import com.ashoo.briefing.BriefingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for the daily plain-English briefing.
 *
 * Returns today's briefing, reusing a same-day one if already generated. The text always
 * ends with the mandatory "consult your doctor" disclaimer, regardless of whether it came
 * from the Claude API or the offline fallback template.
 */
@RestController
@RequestMapping("/api/v1/briefing")
public class BriefingController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    /**
     * Generates or retrieves today's daily briefing.
     *
     * @param demo whether to mark this as a demo briefing (logged separately)
     * @return the briefing text plus metadata
     */
    @GetMapping("/today")
    public BriefingResponse today(@RequestParam(defaultValue = "false") boolean demo) {
        return BriefingResponse.from(briefingService.getTodayBriefing(DEFAULT_USER, demo));
    }
}

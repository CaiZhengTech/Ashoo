package com.ashoo.api;

import com.ashoo.api.dto.BriefingResponse;
import com.ashoo.briefing.BriefingService;
import com.ashoo.common.DemoUsers;
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

    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    /**
     * Generates or retrieves today's daily briefing.
     *
     * @param demo whether to mark this as a demo briefing (logged separately)
     * @param user optional persona to view (default user when omitted/unknown)
     * @return the briefing text plus metadata
     */
    @GetMapping("/today")
    public BriefingResponse today(@RequestParam(defaultValue = "false") boolean demo,
                                  @RequestParam(required = false) String user) {
        return BriefingResponse.from(
                briefingService.getTodayBriefing(DemoUsers.resolve(user), demo));
    }
}

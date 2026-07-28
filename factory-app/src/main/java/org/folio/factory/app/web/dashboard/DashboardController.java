package org.folio.factory.app.web.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardStatsService stats;

    public DashboardController(DashboardStatsService stats) {
        this.stats = stats;
    }

    /** An unlisted window is rejected by the service (422), which caches per window. */
    @GetMapping("/stats")
    public DashboardStats stats(@RequestParam(name = "days", defaultValue = "14") int days) {
        return stats.compute(days);
    }
}

package org.folio.factory.app.web.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "factory.dashboard")
public record DashboardProperties(Duration statsCacheTtl) {

    public DashboardProperties {
        // Zero (or negative) disables caching entirely — the aggregation tests rely on it.
        statsCacheTtl = statsCacheTtl == null ? Duration.ofSeconds(30) : statsCacheTtl;
    }
}

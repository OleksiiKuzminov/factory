package org.folio.factory.core.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heartbeat defaults are a safety balance, not arbitrary numbers: refresh often
 * enough that a slow step is never reaped and driven twice, but stop refreshing soon
 * enough that a wedged one stays reapable.
 */
class EnginePropertiesTest {

    private EngineProperties withHeartbeat(Long intervalSeconds, Long maxSeconds) {
        return new EngineProperties(null, null, null, null, null, intervalSeconds, maxSeconds, null, null);
    }

    @Test
    void unsetHeartbeatPropertiesFallBackToTheSafeCadenceAndBudget() {
        EngineProperties defaults = withHeartbeat(null, null);

        assertThat(defaults.heartbeatIntervalSeconds()).isEqualTo(60L);
        assertThat(defaults.maxStepHeartbeatSeconds()).isEqualTo(3600L);
    }

    @Test
    void aZeroHeartbeatIntervalIsFlooredSoTheSchedulerThreadCannotSpin() {
        assertThat(withHeartbeat(0L, null).heartbeatIntervalSeconds()).isEqualTo(1L);
    }
}

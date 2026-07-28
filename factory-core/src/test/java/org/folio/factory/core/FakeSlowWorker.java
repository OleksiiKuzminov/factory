package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Test fixture: sleeps for the step's configured {@code sleep_millis}, so a step
 * that outlives a (deliberately short) lease timeout can be observed racing the
 * reaper.
 */
@Component
public class FakeSlowWorker implements AgentWorker {

    @Override
    public String id() {
        return "slow-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        long sleepMillis = Long.parseLong(context.configString("sleep_millis", "0"));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionException("slow worker interrupted");
        }
        Map<String, String> outputs = new HashMap<>();
        for (String outputName : context.expectedOutputs()) {
            outputs.put(outputName, "slept " + sleepMillis + "ms");
        }
        return new AgentResult(outputs, Map.of());
    }
}

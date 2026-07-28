package org.folio.factory.testfactory;

import org.folio.factory.core.registry.FlowDescriptorParser;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The descriptor is the flow, so its shape is behaviour: parse the shipped YAML
 * with the real parser rather than string-matching it.
 */
class TestFactoryFlowDescriptorTest {

    private final FlowDescriptor flow = new FlowDescriptorParser().parse(descriptorYaml(), "flows/test-factory.yaml");

    @Test
    void generatedScriptsAreReviewedByAHumanBeforeAnythingExecutesThem() {
        int gateIndex = indexOfStep("script-review");
        StepDescriptor gate = flow.step(gateIndex);

        assertThat(gateIndex).isEqualTo(indexOfStep("test-execution") - 1);
        assertThat(gate.type()).isEqualTo(StepType.HITL_GATE);
        assertThat(gate.gate().gateId()).isEqualTo("gate-script-review");
        assertThat(gate.gate().reviewedArtifacts()).contains("test_scripts.md");
    }

    @Test
    void scriptReviewInstructionsSayThatApprovingRunsTheGeneratedCode() {
        String instructions = flow.step(indexOfStep("script-review")).gate().reviewInstructions();

        assertThat(instructions).contains("EXECUTES").contains("reference environment");
    }

    @Test
    void resultsSignOffStillHappensAfterExecutionAndTheFlowNowHasThreeGates() {
        assertThat(indexOfStep("qa-signoff")).isGreaterThan(indexOfStep("test-execution"));
        assertThat(flow.step(indexOfStep("qa-signoff")).gate().gateId()).isEqualTo("gate-2-signoff");
        assertThat(flow.gateCount()).isEqualTo(3);
    }

    private int indexOfStep(String stepId) {
        for (int i = 0; i < flow.agentChain().size(); i++) {
            if (stepId.equals(flow.step(i).stepId())) {
                return i;
            }
        }
        throw new AssertionError("Flow has no step '" + stepId + "'");
    }

    private static String descriptorYaml() {
        try (InputStream in = TestFactoryFlowDescriptorTest.class.getResourceAsStream("/flows/test-factory.yaml")) {
            if (in == null) {
                throw new AssertionError("flows/test-factory.yaml is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

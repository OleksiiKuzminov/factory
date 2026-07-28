package org.folio.factory.app.web;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusControllerTest {

    private record FakeConnector(String connectorName, boolean isConfigured) implements ConnectorHealth {
    }

    @Mock
    private FlowRegistry flowRegistry;

    @Test
    void status_reportsEngineFlagConnectorStatesAndFlows() {
        when(flowRegistry.all()).thenReturn(List.of(
                new FlowDescriptor("test-factory", "Test Factory", "1.0.0", null, null, null, null, null)));
        EngineProperties engine = new EngineProperties(false, null, null, null, null, null, null, null, null);
        StatusController controller = new StatusController(
                List.of(new FakeConnector("jira", true), new FakeConnector("github", false)),
                flowRegistry, engine);

        Map<String, Object> status = controller.status();

        assertThat(status.get("engineEnabled")).isEqualTo(false);
        assertThat(status.get("connectors"))
                .isEqualTo(Map.of("jira", "configured", "github", "not configured"));
        assertThat(status.get("flows"))
                .isEqualTo(List.of(Map.of("id", "test-factory", "name", "Test Factory", "version", "1.0.0")));
    }

    @Test
    void status_engineEnabledByDefaultWithNoConnectorsOrFlows() {
        when(flowRegistry.all()).thenReturn(List.of());
        EngineProperties engine = new EngineProperties(null, null, null, null, null, null, null, null, null);
        StatusController controller = new StatusController(List.of(), flowRegistry, engine);

        Map<String, Object> status = controller.status();

        assertThat(status.get("engineEnabled")).isEqualTo(true);
        assertThat(status.get("connectors")).isEqualTo(Map.of());
        assertThat(status.get("flows")).isEqualTo(List.of());
    }
}

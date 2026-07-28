package org.folio.factory.testfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.worker.TestAutomationAgentWorker;
import org.folio.factory.testfactory.worker.TestExecutionWorker;
import org.folio.factory.testfactory.worker.TestFactoryFinalizerWorker;
import org.folio.factory.testfactory.worker.TestSpecAgentWorker;
import org.folio.factory.testfactory.worker.TriageAgentWorker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test Factory plugin wiring. The flow itself is data ({@code flows/test-factory.yaml});
 * this class only assembles the worker beans the descriptor references.
 */
@Configuration
@EnableConfigurationProperties(TestFactoryProperties.class)
public class TestFactoryConfiguration {

    @Bean
    public TriageAgentWorker triageAgentWorker(ChatClient.Builder chatClientBuilder,
                                               JiraConnector jiraConnector,
                                               FrontmatterCodec frontmatterCodec) {
        return new TriageAgentWorker(chatClientBuilder.build(), jiraConnector, frontmatterCodec);
    }

    @Bean
    public TestSpecAgentWorker testSpecAgentWorker(ChatClient.Builder chatClientBuilder,
                                                   FrontmatterCodec frontmatterCodec,
                                                   JsonMapper jsonMapper) {
        return new TestSpecAgentWorker(chatClientBuilder.build(), frontmatterCodec, jsonMapper);
    }

    @Bean
    public TestAutomationAgentWorker testAutomationAgentWorker(ChatClient.Builder chatClientBuilder,
                                                               ScriptBundleCodec bundleCodec,
                                                               TestFactoryProperties properties) {
        return new TestAutomationAgentWorker(chatClientBuilder.build(), bundleCodec, properties);
    }

    @Bean
    public TestExecutionWorker testExecutionWorker(ScriptBundleCodec bundleCodec,
                                                   FrontmatterCodec frontmatterCodec,
                                                   TestFactoryProperties properties,
                                                   JsonMapper jsonMapper) {
        return new TestExecutionWorker(bundleCodec, frontmatterCodec, properties, jsonMapper);
    }

    @Bean
    public TestFactoryFinalizerWorker testFactoryFinalizerWorker(JiraConnector jiraConnector,
                                                                 GitHubConnector gitHubConnector,
                                                                 TestRailConnector testRailConnector,
                                                                 FrontmatterCodec frontmatterCodec,
                                                                 ScriptBundleCodec bundleCodec,
                                                                 TestFactoryProperties properties,
                                                                 WorkerAuditSink auditSink,
                                                                 EngineMetrics engineMetrics) {
        return new TestFactoryFinalizerWorker(jiraConnector, gitHubConnector, testRailConnector,
                frontmatterCodec, bundleCodec, properties, auditSink, engineMetrics);
    }
}

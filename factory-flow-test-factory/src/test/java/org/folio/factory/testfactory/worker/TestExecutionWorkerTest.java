package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.factory.testfactory.worker.TestExecutionWorker.MAX_CONSOLE_LOG_CHARS;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestExecutionWorkerTest {

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(frontmatterCodec);

    private TestExecutionWorker worker(String baseUrl, String karateJar) {
        TestFactoryProperties properties = new TestFactoryProperties(
                new TestFactoryProperties.Execution(baseUrl, karateJar), "o/r", "main", null, 55L);
        return new TestExecutionWorker(bundleCodec, frontmatterCodec, properties,
                JsonMapper.builder().build());
    }

    private AgentContext contextWith(ScriptBundle bundle) {
        return new AgentContext(UUID.randomUUID(), "execute", Map.of(
                "test_scripts.md", new ArtifactContent("test_scripts.md", 1, "text/markdown",
                        bundleCodec.render(bundle))),
                null, Map.of(), List.of("test_results.md"));
    }

    @Test
    void unconfiguredExecutionProducesAdvisoryResultsForEveryCase() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01", "TC-02"),
                        "Feature: A\n  Scenario: TC-01 works"),
                new ScriptBundle.ScriptFile("features/b.feature", List.of("TC-03"),
                        "Feature: B\n  Scenario: TC-03 works")));

        AgentResult result = worker(null, null).execute(contextWith(bundle));

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("test_results.md"));
        assertThat(parsed.metadata().path("mode").asString("")).isEqualTo("ADVISORY");
        assertThat(parsed.metadata().path("totals").path("not_executed").asInt()).isEqualTo(3);
        List<String> statuses = new ArrayList<>();
        for (JsonNode caseResult : parsed.metadata().path("case_results")) {
            statuses.add(caseResult.path("status").asString(""));
        }
        assertThat(statuses).hasSize(3).containsOnly("NOT_EXECUTED");
        assertThat(parsed.body()).contains("FACTORY_TEST_FACTORY_EXECUTION_BASE_URL");
    }

    @Test
    void refusesToRunAScriptBundleWithATraversalPathBeforeLaunchingAnything() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("../evil.feature", List.of("TC-01"),
                        "Feature: E\n  Scenario: TC-01 escapes")));

        assertThatThrownBy(() -> worker("http://localhost:9999", "/no/such/karate.jar")
                .execute(contextWith(bundle)))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Refusing to execute generated script path")
                .hasMessageContaining("../evil.feature");
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {
            "/etc/cron.d/payload.feature",
            "../evil.feature",
            "features/../../evil.feature",
            "-h.feature",
            "--backup-dir=/tmp/pwned.feature",
            "~/pwned.feature",
            "features\\pwned.feature",
            "features/pwned .feature",
            "features/pwned.feature\n-h",
            "features/pwned.txt",
            ".feature"})
    void scriptArgument_hostilePath_isRejectedNamingTheOffendingPath(String path) {
        assertThatThrownBy(() -> TestExecutionWorker.scriptArgument(path))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Refusing to execute generated script path")
                .hasMessageContaining(String.valueOf(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.feature", "features/a.feature", "features/sub-dir/agreements_v2.feature"})
    void scriptArgument_ordinaryRelativePath_isPassedInAFormNoCliCanReadAsAnOption(String path) {
        assertThat(TestExecutionWorker.scriptArgument(path)).isEqualTo("./" + path);
    }

    @Test
    void karateCommandPassesTheReferenceBaseUrlAsASystemPropertyAndScriptsAsDotSlashPaths(@TempDir Path workDir) {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01"), "Feature: A"),
                new ScriptBundle.ScriptFile("features/b.feature", List.of("TC-02"), "Feature: B")));

        List<String> command = TestExecutionWorker.karateCommand(
                new TestFactoryProperties.Execution("https://ref.example.org", "/opt/karate.jar"),
                workDir, bundle);

        assertThat(command).containsExactly("java", "-DbaseUrl=https://ref.example.org",
                "-jar", "/opt/karate.jar", "-o", workDir.resolve("reports").toString(),
                "./features/a.feature", "./features/b.feature");
    }

    @Test
    void karateCommandRefusesToBuildArgvContainingAnOptionLookingScriptPath(@TempDir Path workDir) {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("--configdir=/etc.feature", List.of("TC-01"), "Feature: A")));

        assertThatThrownBy(() -> TestExecutionWorker.karateCommand(
                new TestFactoryProperties.Execution("https://ref.example.org", "/opt/karate.jar"),
                workDir, bundle))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("--configdir=/etc.feature");
    }

    @Test
    void childEnvironmentCarriesNoneOfTheFactorySecretsIntoTheGeneratedCodeRunner() {
        Map<String, String> parentEnvironment = Map.of(
                "ANTHROPIC_API_KEY", "sk-ant-secret",
                "FACTORY_CONNECTORS_GITHUB_TOKEN", "ghp-secret",
                "FACTORY_DB_PASSWORD", "hunter2",
                "PATH", "/usr/bin",
                "HOME", "/home/factory",
                "JAVA_HOME", "/opt/java",
                "LANG", "C.UTF-8",
                "TMPDIR", "/tmp");

        Map<String, String> child = TestExecutionWorker.childEnvironment(parentEnvironment);

        assertThat(child).containsOnlyKeys("PATH", "HOME", "JAVA_HOME", "LANG", "TMPDIR");
        assertThat(child.values()).doesNotContain("sk-ant-secret", "ghp-secret", "hunter2");
        assertThat(child.get("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    void childEnvironmentOmitsMinimalVariablesTheParentDoesNotDefine() {
        Map<String, String> child = TestExecutionWorker.childEnvironment(Map.of("PATH", "/usr/bin"));

        assertThat(child).containsOnlyKeys("PATH");
    }

    /**
     * The containment guarantee only holds where the process is actually built: a
     * ProcessBuilder inherits the factory's own environment — Anthropic key, connector
     * tokens, database password — until that is cleared and repopulated from the
     * allowlist. Asserting {@link TestExecutionWorker#childEnvironment} alone would
     * still pass with both scrub lines deleted.
     */
    @Test
    void karateProcessLaunchesTheRunnerWithOnlyTheAllowlistedEnvironment(@TempDir Path workDir) {
        Map<String, String> parentEnvironment = Map.of(
                "ANTHROPIC_API_KEY", "sk-ant-secret",
                "FACTORY_CONNECTORS_GITHUB_TOKEN", "ghp-secret",
                "FACTORY_DB_PASSWORD", "hunter2",
                "PATH", "/usr/bin",
                "HOME", "/home/factory");
        Path consoleLog = workDir.resolve("karate-console.log");

        ProcessBuilder processBuilder = TestExecutionWorker.karateProcess(
                List.of("java", "-jar", "/opt/karate.jar"), workDir, consoleLog, parentEnvironment);

        assertThat(processBuilder.environment())
                .containsOnlyKeys("PATH", "HOME")
                .doesNotContainKeys("ANTHROPIC_API_KEY", "FACTORY_CONNECTORS_GITHUB_TOKEN", "FACTORY_DB_PASSWORD");
        assertThat(processBuilder.environment().values()).doesNotContain("sk-ant-secret", "ghp-secret", "hunter2");
        assertThat(processBuilder.directory()).isEqualTo(workDir.toFile());
        // Console output goes to a file, never a pipe, or the timeout could not fire.
        assertThat(processBuilder.redirectOutput().file()).isEqualTo(consoleLog.toFile());
        assertThat(processBuilder.redirectErrorStream()).isTrue();
    }

    private static boolean javaOnPath() {
        try {
            new ProcessBuilder("java", "-version").start().destroy();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void failedRunnerWithoutSummaryFallsBackToExitCodeAndMarksCasesFailed() {
        assumeTrue(javaOnPath(), "java executable not on PATH");

        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01", "TC-02"),
                        "Feature: A\n  Scenario: TC-01 works")));

        AgentResult result = worker("http://localhost:9999", "/no/such/karate.jar")
                .execute(contextWith(bundle));

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("test_results.md"));
        assertThat(parsed.metadata().path("mode").asString("")).isEqualTo("EXECUTED");
        assertThat(parsed.metadata().path("exit_code").asInt()).isNotZero();
        assertThat(parsed.metadata().path("totals").path("passed").asInt()).isZero();
        assertThat(parsed.metadata().path("totals").path("failed").asInt()).isEqualTo(2);
        assertThat(parsed.metadata().path("features").path("passed").asInt()).isZero();
        assertThat(parsed.metadata().path("features").path("failed").asInt()).isZero();
        List<String> statuses = new ArrayList<>();
        for (JsonNode caseResult : parsed.metadata().path("case_results")) {
            statuses.add(caseResult.path("status").asString(""));
        }
        assertThat(statuses).hasSize(2).containsOnly("FAILED");
        assertThat(parsed.body()).contains("<details><summary>Console output</summary>");
    }

    @Test
    void truncateConsole_shortOutput_returnedUnchanged() {
        String console = "Karate run finished: 3 passed, 0 failed";

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_exactlyAtLimit_returnedUnchanged() {
        String console = "x".repeat(MAX_CONSOLE_LOG_CHARS);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_overLimit_keepsTailAndSaysHowMuchWasOmitted() {
        String marker = "FINAL-KARATE-SUMMARY-MARKER-XY";
        String console = "a".repeat(MAX_CONSOLE_LOG_CHARS + 500 - marker.length()) + marker;

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).endsWith(marker);
        // Pin the full phrase: a bare contains("500") would also match the total-length
        // figure (20500) and pass with a wrong omitted-count computation.
        assertThat(result).contains("truncated")
                .contains("500 of " + console.length() + " characters omitted");
        // The input has no newlines, so the first blank line is the header/payload boundary.
        String payload = result.substring(result.indexOf("\n\n") + 2);
        assertThat(payload).isEqualTo(console.substring(console.length() - MAX_CONSOLE_LOG_CHARS));
    }

    @Test
    void truncateConsole_overLimit_boundedTotalLength() {
        String console = "b".repeat(MAX_CONSOLE_LOG_CHARS + 500);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result.length()).isLessThan(MAX_CONSOLE_LOG_CHARS + 200);
    }
}

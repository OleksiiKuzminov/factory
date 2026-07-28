package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Non-LLM system worker. When a reference environment and Karate runner are
 * configured, materialises the script bundle to disk and executes it; otherwise
 * runs in advisory mode (the document's Phase 1 default) and records the results
 * as NOT_EXECUTED so the pipeline still reaches QA sign-off.
 */
public class TestExecutionWorker implements AgentWorker {

    public static final String ID = "test-execution-agent";

    private static final Logger log = LoggerFactory.getLogger(TestExecutionWorker.class);
    private static final long EXECUTION_TIMEOUT_MINUTES = 15;
    static final int MAX_CONSOLE_LOG_CHARS = 20_000;

    /** The only parent variables the runner inherits; everything else is a secret leak. */
    static final List<String> INHERITED_ENV_VARS = List.of("PATH", "HOME", "JAVA_HOME", "LANG", "TMPDIR");

    private final ScriptBundleCodec bundleCodec;
    private final FrontmatterCodec frontmatterCodec;
    private final TestFactoryProperties properties;
    private final JsonMapper jsonMapper;

    public TestExecutionWorker(ScriptBundleCodec bundleCodec, FrontmatterCodec frontmatterCodec,
                               TestFactoryProperties properties, JsonMapper jsonMapper) {
        this.bundleCodec = bundleCodec;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        ScriptBundle bundle = bundleCodec.parse(context.requireInput("test_scripts.md").content());

        if (!properties.execution().isConfigured()) {
            return AgentResult.of("test_results.md", advisoryResults(bundle));
        }
        return AgentResult.of("test_results.md", executeWithKarate(bundle));
    }

    private String advisoryResults(ScriptBundle bundle) {
        List<Map<String, Object>> caseResults = new ArrayList<>();
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            for (String caseId : file.caseIds()) {
                caseResults.add(Map.of("case_id", caseId, "status", "NOT_EXECUTED", "file", file.path()));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "ADVISORY");
        metadata.put("totals", Map.of("passed", 0, "failed", 0, "not_executed", caseResults.size()));
        metadata.put("case_results", caseResults);
        String body = """
                # Test Results (Advisory Mode)

                No reference environment is configured, so the generated scripts were **not executed**.
                To enable execution, set `FACTORY_TEST_FACTORY_EXECUTION_BASE_URL` and
                `FACTORY_TEST_FACTORY_EXECUTION_KARATE_JAR`.

                Review the generated scripts for correctness before sign-off.
                """;
        return frontmatterCodec.render(metadata, body);
    }

    private String executeWithKarate(ScriptBundle bundle) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("factory-test-factory-run");
            List<String> command = karateCommand(properties.execution(), workDir, bundle);
            for (ScriptBundle.ScriptFile file : bundle.files()) {
                Path target = workDir.resolve(file.path()).normalize();
                if (!target.startsWith(workDir)) {
                    throw new AgentExecutionException("Script path escapes work directory: " + file.path());
                }
                Files.createDirectories(target.getParent() == null ? workDir : target.getParent());
                Files.writeString(target, file.content());
            }

            Path consoleLog = workDir.resolve("karate-console.log");
            Process process = karateProcess(command, workDir, consoleLog, System.getenv()).start();
            if (!process.waitFor(EXECUTION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AgentExecutionException("Karate execution timed out after "
                        + EXECUTION_TIMEOUT_MINUTES + " minutes");
            }
            String output = Files.exists(consoleLog) ? Files.readString(consoleLog) : "";
            log.info("Karate run finished with exit code {}", process.exitValue());
            return executedResults(bundle, workDir, process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentExecutionException("Karate execution failed: " + e.getMessage(), e);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /**
     * Builds the runner argv. The base URL travels as a system property because the
     * child environment is scrubbed (see {@link #childEnvironment}), and every
     * generated path is validated and passed as {@code ./path} so the CLI cannot
     * parse it as an option.
     */
    static List<String> karateCommand(TestFactoryProperties.Execution execution, Path workDir, ScriptBundle bundle) {
        List<String> command = new ArrayList<>(List.of(
                "java", "-DbaseUrl=" + execution.baseUrl(),
                "-jar", execution.karateJar(),
                "-o", workDir.resolve("reports").toString()));
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            command.add(scriptArgument(file.path()));
        }
        return command;
    }

    /**
     * Builds the child process that runs LLM-authored code. Separate from
     * {@link #executeWithKarate} so a test can inspect the environment the runner would
     * really be launched with: {@link #childEnvironment} on its own proves nothing
     * unless the builder's inherited environment is cleared and repopulated from it.
     */
    static ProcessBuilder karateProcess(List<String> command, Path workDir, Path consoleLog,
                                        Map<String, String> parentEnvironment) {
        // Output goes to a file rather than a pipe: reading a pipe would block until
        // the child exits, which would make the execution timeout dead code for a
        // hung run.
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(consoleLog.toFile());
        processBuilder.environment().clear();
        processBuilder.environment().putAll(childEnvironment(parentEnvironment));
        return processBuilder;
    }

    static String scriptArgument(String path) {
        if (!ScriptBundleCodec.isSafePath(path) || !path.endsWith(".feature")) {
            throw new AgentExecutionException("Refusing to execute generated script path '" + path
                    + "': expected a relative .feature path of [A-Za-z0-9._-] segments");
        }
        return "./" + path;
    }

    /**
     * The runner executes LLM-authored code, so it inherits nothing by default: the
     * factory's own process environment holds the Anthropic key, connector tokens and
     * the database password.
     */
    static Map<String, String> childEnvironment(Map<String, String> parentEnvironment) {
        Map<String, String> child = new LinkedHashMap<>();
        for (String name : INHERITED_ENV_VARS) {
            String value = parentEnvironment.get(name);
            if (value != null) {
                child.put(name, value);
            }
        }
        return child;
    }

    private void deleteRecursively(Path directory) {
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException e) {
            log.warn("Could not clean up temporary directory {}: {}", directory, e.getMessage());
        }
    }

    private String executedResults(ScriptBundle bundle, Path workDir, int exitCode, String consoleOutput) {
        Map<String, Boolean> filePassed = new LinkedHashMap<>();
        int passedFeatures = 0;
        int failedFeatures = 0;
        Path summaryFile = workDir.resolve("reports").resolve("karate-summary-json.txt");
        try {
            JsonNode summary = jsonMapper.readTree(Files.readString(summaryFile));
            for (JsonNode feature : summary.path("featureSummary")) {
                boolean failed = feature.path("failed").asBoolean(false);
                // Features are passed as ./path, so the summary may echo that prefix
                // back; bundle paths carry no prefix.
                String relativePath = feature.path("relativePath").asString("");
                filePassed.put(relativePath.startsWith("./") ? relativePath.substring(2) : relativePath, !failed);
                if (failed) {
                    failedFeatures++;
                } else {
                    passedFeatures++;
                }
            }
        } catch (IOException e) {
            // Fall back to the process exit code when the summary is unavailable.
            log.warn("Karate summary not readable ({}); falling back to exit code", e.getMessage());
        }

        List<Map<String, Object>> caseResults = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            boolean filePass = filePassed.getOrDefault(file.path(), exitCode == 0);
            for (String caseId : file.caseIds()) {
                caseResults.add(Map.of("case_id", caseId,
                        "status", filePass ? "PASSED" : "FAILED", "file", file.path()));
                if (filePass) {
                    passed++;
                } else {
                    failed++;
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "EXECUTED");
        metadata.put("exit_code", exitCode);
        metadata.put("totals", Map.of("passed", passed, "failed", failed, "not_executed", 0));
        metadata.put("features", Map.of("passed", passedFeatures, "failed", failedFeatures));
        metadata.put("case_results", caseResults);
        String body = "# Test Results\n\nKarate exit code: " + exitCode
                + "\n\n<details><summary>Console output</summary>\n\n````\n"
                + truncateConsole(consoleOutput.strip()) + "\n````\n</details>\n";
        return frontmatterCodec.render(metadata, body);
    }

    /**
     * Bounds the console log embedded into the artifact, keeping the tail (where
     * Karate's summary and last failures are). Authoritative pass/fail data lives in
     * the frontmatter, parsed from the summary JSON, so truncation only trims the
     * human-readable diagnostic. Defence-in-depth in front of the max-artifact-bytes cap.
     */
    static String truncateConsole(String console) {
        if (console.length() <= MAX_CONSOLE_LOG_CHARS) {
            return console;
        }
        int omitted = console.length() - MAX_CONSOLE_LOG_CHARS;
        return "[console output truncated: " + omitted + " of " + console.length()
                + " characters omitted; showing last " + MAX_CONSOLE_LOG_CHARS + "]\n\n"
                + console.substring(console.length() - MAX_CONSOLE_LOG_CHARS);
    }
}

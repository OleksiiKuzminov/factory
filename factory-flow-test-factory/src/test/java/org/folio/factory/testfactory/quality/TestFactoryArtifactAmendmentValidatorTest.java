package org.folio.factory.testfactory.quality;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFactoryArtifactAmendmentValidatorTest {

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(frontmatterCodec);
    private final TestFactoryArtifactAmendmentValidator validator =
            new TestFactoryArtifactAmendmentValidator(frontmatterCodec, bundleCodec);

    @Test
    void acceptsAmendmentThatKeepsFrontmatterIntact() {
        String amended = frontmatterCodec.render(Map.of("issue_key", "ERM-1"), "# Amended plan");

        assertThatCode(() -> validator.validate("test_plan.md", amended))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope_manifest.md", "test_plan.md", "test_results.md"})
    void rejectsAmendmentThatBreaksFrontmatter(String artifactName) {
        assertThatThrownBy(() -> validator.validate(artifactName, "reviewer deleted the frontmatter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amended '" + artifactName + "' is not structurally valid")
                .hasMessageContaining("must start with ---")
                .cause().isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void rejectsScriptBundleAmendmentWithoutFileSections() {
        String amended = frontmatterCodec.render(Map.of("framework", "karate"), "all file sections removed");

        assertThatThrownBy(() -> validator.validate("test_scripts.md", amended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no '## file:' sections")
                .cause().isInstanceOf(ArtifactFormatException.class);
    }

    /**
     * The script-review gate lets a reviewer rewrite the bundle, and the amended
     * version is the one the execution step writes to disk and runs. A path that
     * escapes the work directory has to be refused while a human is still there.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "../evil.feature",
            "/etc/cron.d/payload.feature",
            "features/../../evil.feature",
            "~/pwned.feature",
            "--configdir=/etc.feature"})
    void rejectsAnAmendedBundleWhoseFilePathEscapesTheWorkDirectory(String path) {
        String amended = bundleCodec.render(new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile(path, List.of("TC-01"), "Feature: E\n  Scenario: TC-01"))));

        assertThatThrownBy(() -> validator.validate("test_scripts.md", amended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe file path")
                .hasMessageContaining(path);
    }

    @Test
    void acceptsAnAmendedBundleWithOrdinaryRelativePaths() {
        String amended = bundleCodec.render(new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/sub-dir/agreements_v2.feature", List.of("TC-01"),
                        "Feature: A\n  Scenario: TC-01 works"))));

        assertThatCode(() -> validator.validate("test_scripts.md", amended)).doesNotThrowAnyException();
    }

    @Test
    void ignoresArtifactsWithoutAStructuralContract() {
        assertThatCode(() -> validator.validate("sync_report.md", "free-form garbage %% not yaml"))
                .doesNotThrowAnyException();
    }
}

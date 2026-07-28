package org.folio.factory.testfactory.quality;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.hitl.ArtifactAmendmentValidator;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Ensures reviewer amendments to Test Factory artifacts keep the machine-readable
 * structure intact: downstream agents re-parse the frontmatter, so an edit that
 * breaks it must be rejected at decision time (422), not discovered mid-pipeline.
 *
 * <p>An amended {@code test_scripts.md} is also the bundle the execution step writes
 * to disk and runs, so its file paths are checked here too.</p>
 */
@Component
public class TestFactoryArtifactAmendmentValidator implements ArtifactAmendmentValidator {

    private static final Set<String> FRONTMATTER_ARTIFACTS =
            Set.of("scope_manifest.md", "test_plan.md", "test_results.md");

    private final FrontmatterCodec frontmatterCodec;
    private final ScriptBundleCodec bundleCodec;

    public TestFactoryArtifactAmendmentValidator(FrontmatterCodec frontmatterCodec, ScriptBundleCodec bundleCodec) {
        this.frontmatterCodec = frontmatterCodec;
        this.bundleCodec = bundleCodec;
    }

    @Override
    public void validate(String artifactName, String content) {
        try {
            if (FRONTMATTER_ARTIFACTS.contains(artifactName)) {
                frontmatterCodec.parse(content);
            } else if ("test_scripts.md".equals(artifactName)) {
                requireSafePaths(bundleCodec.parse(content));
            }
        } catch (ArtifactFormatException e) {
            throw new IllegalArgumentException("Amended '" + artifactName
                    + "' is not structurally valid: " + e.getMessage(), e);
        }
    }

    /**
     * A reviewer can rewrite the bundle at the script-review gate, and the amended
     * version is the one that gets executed: a traversal or absolute path would put a
     * reviewer-supplied file anywhere the runner can write. The execution worker
     * refuses it again before launching anything, but the gate is where a human is
     * still there to be told which path is wrong.
     */
    private void requireSafePaths(ScriptBundle bundle) {
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            if (!ScriptBundleCodec.isSafePath(file.path())) {
                throw new IllegalArgumentException("Amended 'test_scripts.md' declares an unsafe file path '"
                        + file.path() + "': expected a relative path of [A-Za-z0-9._-] segments");
            }
        }
    }
}

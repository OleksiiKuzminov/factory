package org.folio.factory.testfactory.artifact;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders and parses the {@code test_scripts.md} bundle: frontmatter carries the
 * file manifest, the body carries each file's content in a fenced block under a
 * {@code ## file:} heading. Keeps the "agents communicate only through
 * artifacts" rule while still bundling multiple generated files.
 */
@Component
public class ScriptBundleCodec {

    private static final String FENCE = "````";
    private static final Pattern FILE_SECTION = Pattern.compile(
            "^## file: (.+?)\\s*\\n+" + FENCE + "[a-z]*\\n(.*?)\\n" + FENCE,
            Pattern.MULTILINE | Pattern.DOTALL);

    /**
     * Every path segment must start with an alphanumeric or underscore, which leaves no
     * room for a {@code ..} traversal, an absolute path, a Windows separator or a leading
     * {@code -}. Bundle paths are written to disk and passed as argv elements of the
     * Karate CLI, so anything that could escape the work directory or read as an option
     * is rejected outright rather than sanitised.
     */
    private static final Pattern SAFE_PATH =
            Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*");

    private final FrontmatterCodec frontmatterCodec;

    public ScriptBundleCodec(FrontmatterCodec frontmatterCodec) {
        this.frontmatterCodec = frontmatterCodec;
    }

    public static boolean isSafePath(String path) {
        return path != null && SAFE_PATH.matcher(path).matches();
    }

    public String render(ScriptBundle bundle) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("framework", bundle.framework());
        metadata.put("files", bundle.files().stream()
                .map(f -> Map.of("path", f.path(), "case_ids", f.caseIds()))
                .toList());
        StringBuilder body = new StringBuilder();
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            body.append("## file: ").append(file.path()).append("\n\n")
                    .append(FENCE).append("\n")
                    .append(file.content().strip()).append("\n")
                    .append(FENCE).append("\n\n");
        }
        return frontmatterCodec.render(metadata, body.toString());
    }

    public ScriptBundle parse(String content) {
        Frontmatter frontmatter = frontmatterCodec.parse(content);
        String framework = frontmatter.metadata().path("framework").asString("");

        Map<String, List<String>> caseIdsByPath = new LinkedHashMap<>();
        frontmatter.metadata().path("files").forEach(fileNode -> {
            List<String> caseIds = new ArrayList<>();
            fileNode.path("case_ids").forEach(caseId -> caseIds.add(caseId.asString("")));
            caseIdsByPath.put(fileNode.path("path").asString(""), caseIds);
        });

        List<ScriptBundle.ScriptFile> files = new ArrayList<>();
        Matcher matcher = FILE_SECTION.matcher(frontmatter.body());
        while (matcher.find()) {
            String path = matcher.group(1);
            files.add(new ScriptBundle.ScriptFile(path,
                    caseIdsByPath.getOrDefault(path, List.of()), matcher.group(2)));
        }
        if (files.isEmpty()) {
            throw new ArtifactFormatException("Script bundle contains no '## file:' sections");
        }
        return new ScriptBundle(framework, files);
    }
}

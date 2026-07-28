package org.folio.factory.core.service;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.repository.ArtifactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned, immutable artifact registry. Writing an artifact that already exists
 * creates the next version; existing rows are never modified.
 */
@Service
public class ArtifactStore {

    private final ArtifactRepository repository;
    private final AuditLog auditLog;
    private final LimitsProperties limits;

    public ArtifactStore(ArtifactRepository repository, AuditLog auditLog, LimitsProperties limits) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.limits = limits;
    }

    @Transactional
    public Artifact put(UUID executionId, String name, String content, String contentType, String createdBy) {
        int size = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        if (size > limits.maxArtifactBytes()) {
            throw new IllegalArgumentException("Artifact '" + name + "' is " + size
                    + " bytes, exceeding the " + limits.maxArtifactBytes() + "-byte limit");
        }
        // Allocate the version under a lock on this (executionId, name): concurrent
        // writers of the same artifact would otherwise read the same max and collide
        // on uq_artifact_version, losing one write. Released when this transaction ends.
        repository.lockVersionSequence(executionId + "/" + name);
        int nextVersion = repository.findTopByExecutionIdAndNameOrderByVersionDesc(executionId, name)
                .map(a -> a.getVersion() + 1)
                .orElse(1);
        Artifact artifact = new Artifact(executionId, name, nextVersion, contentType, content,
                sha256(content), createdBy);
        Artifact saved = repository.save(artifact);
        auditLog.record(executionId, AuditEventType.ARTIFACT_WRITTEN, null, createdBy,
                Map.of("name", name, "version", nextVersion, "sha256", saved.getSha256()));
        return saved;
    }

    @Transactional
    public Artifact putMarkdown(UUID executionId, String name, String content, String createdBy) {
        return put(executionId, name, content, "text/markdown", createdBy);
    }

    public Optional<Artifact> getLatest(UUID executionId, String name) {
        return repository.findTopByExecutionIdAndNameOrderByVersionDesc(executionId, name);
    }

    public Optional<Artifact> get(UUID executionId, String name, int version) {
        return repository.findByExecutionIdAndNameAndVersion(executionId, name, version);
    }

    public List<Artifact> allForExecution(UUID executionId) {
        return repository.findByExecutionIdOrderByNameAscVersionAsc(executionId);
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

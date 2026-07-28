package org.folio.factory.core.repository;

import org.folio.factory.core.domain.Artifact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository: artifacts are immutable, so no update or delete
 * operations are exposed.
 */
public interface ArtifactRepository extends Repository<Artifact, UUID> {

    Artifact save(Artifact artifact);

    Optional<Artifact> findTopByExecutionIdAndNameOrderByVersionDesc(UUID executionId, String name);

    /**
     * Blocks until the calling transaction owns the version sequence for one
     * (executionId, name), and holds it until that transaction ends.
     *
     * <p>Read-then-insert cannot be made race-free in SQL alone: {@code max(version)}
     * takes no lock, so under READ COMMITTED two writers of the same artifact both
     * compute the same next version and one dies on {@code uq_artifact_version} — a
     * lost write for a duplicate driver or a concurrent HITL amendment. The lock is
     * keyed on a hash of the pair, so a collision only serializes unrelated artifacts.
     * The cast keeps the void-returning lock function mappable by JDBC.</p>
     */
    @Query(value = "SELECT cast(pg_advisory_xact_lock(hashtextextended(cast(:key as text), 0)) as text)",
            nativeQuery = true)
    String lockVersionSequence(@Param("key") String key);

    Optional<Artifact> findByExecutionIdAndNameAndVersion(UUID executionId, String name, int version);

    List<Artifact> findByExecutionIdOrderByNameAscVersionAsc(UUID executionId);

    Optional<Artifact> findById(UUID id);

    // Slice, not Page: artifacts are insert-only versioned rows, so the table is
    // unbounded and a per-request count(*) would degrade over time.
    Slice<Artifact> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Slice<Artifact> findByNameOrderByCreatedAtDescIdDesc(String name, Pageable pageable);
}

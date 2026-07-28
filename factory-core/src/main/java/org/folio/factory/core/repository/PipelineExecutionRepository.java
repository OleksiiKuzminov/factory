package org.folio.factory.core.repository;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, UUID> {

    /**
     * Claims runnable executions with a row lock, skipping rows already claimed by
     * a concurrent poller. Must be called inside a transaction.
     */
    @Query(value = """
            SELECT * FROM pipeline_execution
            WHERE status = 'PENDING' AND next_run_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PipelineExecution> claimRunnable(@Param("limit") int limit);

    List<PipelineExecution> findByStatusAndUpdatedAtBefore(ExecutionStatus status, Instant threshold);

    List<PipelineExecution> findByStatus(ExecutionStatus status);

    List<PipelineExecution> findByParentExecutionId(UUID parentExecutionId);

    /**
     * Child executions in one of the given states that their parent is still waiting
     * on. Scoped to waiting parents so periodic reconciliation stays cheap as finished
     * executions accumulate over time, and correlated through the parent's
     * awaitingChildExecutionId so only the one invocation the parent is blocked on
     * matches. That correlation is load-bearing — a superseded child (an escalation
     * approval re-invoked the step, or an earlier SUB_FLOW step finished while a later
     * one runs) otherwise keeps matching every sweep, re-escalating a parent that
     * already resumed or re-copying a finished step's outputs. It is a stored pointer
     * rather than a "newest child" comparison because created_at carries the clock of
     * whichever instance inserted the row: NTP steps and inter-instance skew would
     * make the newer child look older.
     */
    @Query("""
            SELECT c FROM PipelineExecution c
            WHERE c.status IN :statuses
              AND c.parentExecutionId IS NOT NULL
              AND EXISTS (SELECT p.id FROM PipelineExecution p
                          WHERE p.id = c.parentExecutionId
                            AND p.status = :parentStatus
                            AND p.awaitingChildExecutionId = c.id)
            """)
    List<PipelineExecution> findChildrenWithWaitingParent(
            @Param("statuses") List<ExecutionStatus> statuses,
            @Param("parentStatus") ExecutionStatus parentStatus);

    Page<PipelineExecution> findByStatus(ExecutionStatus status, Pageable pageable);

    Page<PipelineExecution> findByFlowId(String flowId, Pageable pageable);

    Page<PipelineExecution> findByStatusAndFlowId(ExecutionStatus status, String flowId, Pageable pageable);

    /**
     * Filtered execution search: dispatches to the narrowest finder for the given
     * combination of a status and flow id. A null status or a null/blank flow id is
     * treated as "no filter" on that dimension.
     */
    default Page<PipelineExecution> search(ExecutionStatus status, String flowId, Pageable pageable) {
        boolean hasStatus = status != null;
        boolean hasFlow = flowId != null && !flowId.isBlank();
        if (hasStatus && hasFlow) {
            return findByStatusAndFlowId(status, flowId, pageable);
        }
        if (hasStatus) {
            return findByStatus(status, pageable);
        }
        if (hasFlow) {
            return findByFlowId(flowId, pageable);
        }
        return findAll(pageable);
    }

    long countByStatus(ExecutionStatus status);

    @Query("select e.status, count(e) from PipelineExecution e group by e.status")
    List<Object[]> countGroupedByStatus();

    boolean existsByFlowIdAndDedupKeyAndIdNotAndStatusNotIn(
            String flowId, String dedupKey, UUID id, Collection<ExecutionStatus> statuses);

    long countByCreatedAtGreaterThanEqual(Instant threshold);

    /**
     * Existing executions for (flowId, dedupKey) that are still within the dedup
     * window OR still active (non-terminal). Most recent first.
     */
    @Query("""
            SELECT e FROM PipelineExecution e
            WHERE e.flowId = :flowId
              AND e.dedupKey = :dedupKey
              AND (e.createdAt >= :since OR e.status NOT IN :terminal)
            ORDER BY e.createdAt DESC
            """)
    List<PipelineExecution> findDuplicates(
            @Param("flowId") String flowId,
            @Param("dedupKey") String dedupKey,
            @Param("since") Instant since,
            @Param("terminal") List<ExecutionStatus> terminal);
}

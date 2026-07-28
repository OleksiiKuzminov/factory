package org.folio.factory.core.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * The only audit surface agent workers get. Workers live in flow plugin modules,
 * so handing them {@code AuditLog} would let any plugin append arbitrary event
 * types under any actor — including {@code system} — to the append-only ledger the
 * control plane's guarantees rest on. This SPI narrows that to the three
 * connector outcomes, with the actor derived from the worker id.
 *
 * <p>{@link #completedDetail} exists so non-idempotent side effects survive a step
 * re-run (bounded retry, lease reap, approved escalation): the ledger is written as
 * each action completes, so it is the durable record of what already happened even
 * when the step never reached its artifact write. It returns the recorded detail
 * rather than a flag because a re-run usually needs the identifier the skipped call
 * produced — a TestRail case id, a pull request URL — to finish the outstanding work.</p>
 */
public interface WorkerAuditSink {

    /** Records a side effect that completed successfully. */
    void connectorAction(UUID executionId, String stepId, String workerId,
                         String connector, String actionKey, String label, String detail);

    /** Records a side effect that was skipped because its connector is not configured. */
    void connectorSkipped(UUID executionId, String stepId, String workerId,
                          String connector, String actionKey, String label, String detail);

    /** Records a side effect that was attempted and failed. */
    void connectorFailed(UUID executionId, String stepId, String workerId,
                         String connector, String actionKey, String label, String detail);

    /**
     * The detail recorded when {@code actionKey} completed for this execution — i.e.
     * a prior attempt of the step recorded it via {@link #connectorAction} — or
     * {@link Optional#empty()} when it did not. A present value may itself be empty:
     * the side effect happened but produced no identifier. Callers must skip the side
     * effect rather than repeat it, and must key each irreversible operation
     * separately: a key spanning several of them cannot be recorded until the last
     * one succeeds, so a failure in between replays the ones that already happened.
     *
     * <p>Stable means stable <em>for the same input</em>. A step that escalates lets a
     * reviewer amend its input artifacts before approving the retry, and under a
     * content-blind key the entry is already recorded — the retry skips the call and
     * reports the sync done while the correction never leaves the building. Fold the
     * consumed artifact's content hash into the key of any call that transmits it
     * ({@code github.pr@<sha12>}), and leave it out of a call that is a pure state
     * change ({@code jira.transition}), which an amendment must not replay.</p>
     *
     * @param actionKey stable identifier for the side effect (e.g. {@code jira.comment}),
     *                  never a human-readable label
     */
    Optional<String> completedDetail(UUID executionId, String connector, String actionKey);
}

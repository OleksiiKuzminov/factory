package org.folio.factory.core.engine;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Periodic recovery sweeps for sub-flow composition, driven by the poller so they
 * are restart-safe and independent of where a child's terminal transition happened.
 *
 * <p>Deliberately a separate bean from {@link SubFlowInvoker}: the per-child recovery
 * units are {@code @Transactional} there, and reaching them through this bean crosses
 * the Spring proxy so each child really commits — or rolls back — on its own. Calling
 * them on {@code this} inside the invoker bypassed the proxy and put an entire sweep
 * in one transaction, where a single unrecoverable child rolled back every other
 * child's recovery in the same pass.</p>
 */
@Component
public class SubFlowReconciler {

    private static final Logger log = LoggerFactory.getLogger(SubFlowReconciler.class);

    private final StateManager stateManager;
    private final SubFlowInvoker subFlowInvoker;

    public SubFlowReconciler(StateManager stateManager, SubFlowInvoker subFlowInvoker) {
        this.stateManager = stateManager;
        this.subFlowInvoker = subFlowInvoker;
    }

    /**
     * Recovers parents whose child completed but whose hand-off was lost (process
     * death between the child's COMPLETED transition and the parent resume). Safe to
     * repeat — the guarded advance refuses duplicates.
     */
    public void reconcileCompletedChildren() {
        for (PipelineExecution child : stateManager.findCompletedChildrenWithWaitingParent()) {
            log.info("Recovering lost sub-flow completion: child {} of parent {}",
                    child.getId(), child.getParentExecutionId());
            try {
                subFlowInvoker.onChildCompleted(child.getId());
            } catch (Exception e) {
                log.error("Could not hand child {} back to parent {}; continuing the sweep",
                        child.getId(), child.getParentExecutionId(), e);
            }
        }
    }

    /**
     * Escalates parents whose child terminated without completing (rejected at a
     * gate, failed, cancelled) so the dead end lands in the review inbox.
     */
    public void escalateParentsOfTerminatedChildren() {
        for (PipelineExecution child : stateManager.findTerminatedChildren()) {
            try {
                subFlowInvoker.escalateParentOfTerminatedChild(child.getId());
            } catch (Exception e) {
                log.error("Could not escalate parent {} of terminated child {}; continuing the sweep",
                        child.getParentExecutionId(), child.getId(), e);
            }
        }
    }
}

package org.folio.factory.app.security;

import java.security.Principal;

/**
 * Resolves the actor recorded in the audit ledger and on artifact versions. The
 * name always comes from the servlet container's authenticated principal — a
 * client-supplied reviewer name would make the ledger's attribution worthless.
 */
public final class CurrentUser {

    /** Recorded when {@code factory.auth.enabled=false}, where no principal exists. */
    public static final String ANONYMOUS = "anonymous";

    private CurrentUser() {
    }

    /**
     * @param principal the container-authenticated caller, or {@code null} when
     *                  authentication is switched off
     */
    public static String name(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ANONYMOUS;
        }
        return principal.getName();
    }
}

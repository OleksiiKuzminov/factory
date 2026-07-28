package org.folio.factory.app.security;

import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    @Test
    void name_authenticatedPrincipal_returnsItsName() {
        assertThat(CurrentUser.name((Principal) () -> "qa-lead")).isEqualTo("qa-lead");
    }

    @Test
    void name_noPrincipal_fallsBackToAnonymous() {
        assertThat(CurrentUser.name(null)).isEqualTo("anonymous");
    }

    @Test
    void name_blankPrincipalName_fallsBackToAnonymousSoTheLedgerNeverGetsABlankActor() {
        assertThat(CurrentUser.name((Principal) () -> "  ")).isEqualTo("anonymous");
    }

    @Test
    void adviceExposesTheUsernameToViews() {
        assertThat(new CurrentUserAdvice().currentUser(() -> "rel-mgr")).isEqualTo("rel-mgr");
    }

    @Test
    void adviceExposesNullSoTheLayoutOmitsTheSessionControls() {
        assertThat(new CurrentUserAdvice().currentUser(null)).isNull();
    }
}

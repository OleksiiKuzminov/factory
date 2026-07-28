package org.folio.factory.app.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTest {

    private static AuthProperties users(String users) {
        return new AuthProperties(null, users, null);
    }

    @Test
    void defaults_authenticationOnAndMetricsPrivate() {
        AuthProperties properties = new AuthProperties(null, null, null);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.metricsPublic()).isFalse();
        assertThat(properties.parseUsers()).isEmpty();
    }

    @Test
    void parseUsers_multiplePairs_keptInDeclarationOrder() {
        assertThat(users("qa-lead:pw-one, rel-mgr:pw-two").parseUsers())
                .containsExactly(Map.entry("qa-lead", "pw-one"), Map.entry("rel-mgr", "pw-two"));
    }

    @Test
    void parseUsers_passwordContainingColons_keptWhole() {
        assertThat(users("qa-lead:{bcrypt}$2a$10$abc:def").parseUsers())
                .containsExactly(Map.entry("qa-lead", "{bcrypt}$2a$10$abc:def"));
    }

    @Test
    void parseUsers_entryWithoutColon_rejectedWithoutEchoingTheEntry() {
        assertThatThrownBy(() -> users("qa-lead:pw-one,justapassword").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 2 of 2")
                .hasMessageContaining("has no ':'")
                .hasMessageNotContaining("justapassword");
    }

    @Test
    void parseUsers_emptyPassword_rejected() {
        assertThatThrownBy(() -> users("qa-lead:").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 1 of 1")
                .hasMessageContaining("has a blank password");
    }

    @Test
    void parseUsers_emptyUsername_rejected() {
        assertThatThrownBy(() -> users(" :pw-one").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 1 of 1")
                .hasMessageContaining("has an empty username");
    }

    @Test
    void parseUsers_usernameOutsideTheCharset_rejectedNamingTheCharset() {
        assertThatThrownBy(() -> users("qa lead:pw-one").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 1 of 1")
                .hasMessageContaining("[A-Za-z0-9._@+-]");
    }

    /**
     * The charset is the whole username rule — the message and every doc say so, and
     * before authentication shipped any name was accepted. A leading '_' or '-' is an
     * ordinary service-account name, so rejecting it would be an undocumented
     * regression whose failure the operator cannot map back to a stated rule.
     */
    @Test
    void parseUsers_usernameStartingWithANonAlphanumeric_accepted() {
        assertThat(users("_svc:pw,-deploy:pw2").parseUsers()).containsOnlyKeys("_svc", "-deploy");
    }

    @Test
    void parseUsers_usernameLongerThan64Characters_accepted() {
        String username = "a".repeat(120);

        assertThat(users(username + ":pw").parseUsers()).containsOnlyKeys(username);
    }

    @Test
    void parseUsers_trailingComma_rejectedRatherThanSilentlyDropped() {
        assertThatThrownBy(() -> users("qa-lead:pw-one,").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    /**
     * ',' separates entries, so a plain password containing one is split into a
     * truncated password plus an account nobody declared. Whenever the stray tail is
     * not a plausible username the parse must refuse rather than register it.
     */
    @Test
    void parseUsers_commaInsideAPlainPassword_rejectedWithoutEchoingTheFragment() {
        assertThatThrownBy(() -> users("qa-lead:s3cr,et!:x").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 2 of 2")
                .hasMessageContaining("[A-Za-z0-9._@+-]")
                .hasMessageContaining("must not contain ','")
                .hasMessageNotContaining("et!");
    }

    @Test
    void parseUsers_bcryptHashSplitByAComma_rejected() {
        assertThatThrownBy(() -> users("qa-lead:{bcrypt}$2a$10$ab,$cd:x").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 2 of 2");
    }

    @Test
    void parseUsers_serviceAccountNames_accepted() {
        assertThat(users("ci_bot:a,release.mgr@folio.org:b,qa-lead+1:c").parseUsers())
                .containsOnlyKeys("ci_bot", "release.mgr@folio.org", "qa-lead+1");
    }

    @Test
    void parseUsers_duplicateUsername_rejected() {
        assertThatThrownBy(() -> users("qa-lead:one,qa-lead:two").parseUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
    }
}

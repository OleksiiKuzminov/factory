package org.folio.factory.app.security;

import org.folio.factory.app.StubLlmConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default deployment posture: authentication on with no accounts configured. One
 * {@code admin} account is generated with a per-boot random password, which is the
 * only thing standing between a fresh container and an unauthenticated HITL approval.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.auth.enabled=true",
        "factory.auth.users="
})
@Import(StubLlmConfiguration.class)
class GeneratedAdminSecurityTest extends AbstractSecurityIntegrationTest {

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void generatedAdminIsTheOnlyAccountAndItsPasswordIsEncoded() {
        assertThat(userDetailsService.loadUserByUsername(SecurityConfiguration.GENERATED_USERNAME).getPassword())
                .as("a plaintext password would be comparable straight out of a heap dump")
                .startsWith("{bcrypt}$2a$");

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
        // InMemoryUserDetailsManager exposes no way to list accounts, so the registry
        // it keys by username is read directly — nothing else proves 'only'.
        assertThat(userDetailsService)
                .asInstanceOf(type(InMemoryUserDetailsManager.class))
                .extracting("users", MAP)
                .as("no second account may be created alongside the generated one")
                .containsOnlyKeys(SecurityConfiguration.GENERATED_USERNAME);
    }

    /**
     * The password must be drawn per boot, not be a constant that ships in the jar.
     * The encoder here is deliberately reversible: bcrypt salts every call, so two
     * hashes of one constant would differ and hide exactly the defect under test.
     */
    @Test
    void twoStartupsGenerateDifferentPasswords() {
        PasswordEncoder revealing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "{noop}" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encodedPassword.equals("{noop}" + rawPassword);
            }
        };
        AuthProperties noAccounts = new AuthProperties(true, "", false);

        String first = new SecurityConfiguration().factoryUserDetailsService(noAccounts, revealing)
                .loadUserByUsername(SecurityConfiguration.GENERATED_USERNAME).getPassword();
        String second = new SecurityConfiguration().factoryUserDetailsService(noAccounts, revealing)
                .loadUserByUsername(SecurityConfiguration.GENERATED_USERNAME).getPassword();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void consoleStillRequiresALoginWithTheGeneratedAccountInPlace() throws Exception {
        mvc.perform(get("/executions")).andExpect(status().is3xxRedirection());
    }
}

package org.folio.factory.app;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The account the tests that run with authentication on sign in as.
 *
 * <p>The password is generated per JVM rather than written into the source. A literal
 * one is indistinguishable from a leaked credential to a secret scanner, and nothing
 * here needs a fixed value — every request is built from these constants. It has to be
 * registered through {@code @DynamicPropertySource} for the same reason: a
 * {@code factory.auth.users=user:password} string in a {@code @SpringBootTest}
 * annotation would put the pair back in the source.</p>
 */
public final class TestAuthCredentials {

    public static final String USER = "qa-lead";
    public static final String PASSWORD = UUID.randomUUID().toString();

    private TestAuthCredentials() {
    }

    /**
     * Turns authentication on with {@link #USER} as the only account.
     *
     * @param extraEntries further {@code user:password} entries to configure alongside it
     */
    public static void register(DynamicPropertyRegistry registry, String... extraEntries) {
        List<String> entries = new ArrayList<>();
        entries.add(USER + ":" + PASSWORD);
        entries.addAll(List.of(extraEntries));
        registry.add("factory.auth.enabled", () -> true);
        registry.add("factory.auth.users", () -> String.join(",", entries));
    }
}

package org.folio.factory.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Console and REST API authentication settings, bound from {@code factory.auth.*}.
 *
 * @param users         comma-separated {@code user:password} pairs. A password that
 *                      already carries an encoder id ({@code {bcrypt}$2a$...}) is used
 *                      as given; anything else is encoded at startup. A plain password
 *                      therefore cannot contain the {@code ,} separator — the parse
 *                      would split it into a truncated password plus a second account.
 *                      Empty means a random password is generated for a single
 *                      {@code admin} account.
 * @param metricsPublic lets unauthenticated scrapers read the Prometheus/metrics
 *                      endpoints; health probes are public regardless
 */
@ConfigurationProperties(prefix = "factory.auth")
public record AuthProperties(Boolean enabled, String users, Boolean metricsPublic) {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@+-]+");

    public AuthProperties {
        enabled = enabled == null || enabled;
        metricsPublic = metricsPublic != null && metricsPublic;
        users = users == null ? "" : users.strip();
    }

    /**
     * @return declared username to password, in declaration order
     * @throws IllegalStateException on any malformed or duplicated entry — an account
     *         dropped silently would look like a working login that never authenticates
     */
    public Map<String, String> parseUsers() {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (users.isBlank()) {
            return parsed;
        }
        String[] entries = users.split(",", -1);
        for (int i = 0; i < entries.length; i++) {
            // Entry text is never echoed into the failure: a pair typed without its
            // username would put the password in the startup log.
            String where = "factory.auth.users entry " + (i + 1) + " of " + entries.length;
            String entry = entries[i].strip();
            if (entry.isEmpty()) {
                throw new IllegalStateException(where + " is empty; expected user:password");
            }
            int separator = entry.indexOf(':');
            if (separator < 0) {
                throw new IllegalStateException(where + " has no ':'; expected user:password");
            }
            String username = entry.substring(0, separator).strip();
            String password = entry.substring(separator + 1);
            if (username.isEmpty()) {
                throw new IllegalStateException(where + " has an empty username; expected user:password");
            }
            if (password.isBlank()) {
                throw new IllegalStateException(where + " has a blank password; expected user:password");
            }
            // A left-hand side outside the login charset is almost always the tail of a
            // plain password that contained the ',' separator, so it is refused rather
            // than registered as an account nobody declared.
            if (!USERNAME.matcher(username).matches()) {
                throw new IllegalStateException(where + " has a username outside [A-Za-z0-9._@+-];"
                        + " a plain password must not contain ','");
            }
            if (parsed.put(username, password) != null) {
                throw new IllegalStateException("factory.auth.users declares user '" + username + "' twice");
            }
        }
        return parsed;
    }
}

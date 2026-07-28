package org.folio.factory.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SEC-1: authenticates the console and the REST API. Without it anyone who can reach
 * the port can approve a HITL gate, which would leave the append-only ledger's actor
 * column proving nothing.
 *
 * <p>Four chains: read-only {@code GET /api/**} also honours the console's session so
 * the dashboard's own XHR keeps working; every other {@code /api/**} request is
 * stateless HTTP Basic with CSRF off (webhooks excepted — they carry their own
 * shared-secret/HMAC authentication); {@code /actuator/**} is Basic with only the
 * health probes (and optionally the metrics endpoints) public; and the console is a
 * CSRF-protected form login that also accepts Basic so scripts need no session.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

    static final String GENERATED_USERNAME = "admin";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    private static final String[] PUBLIC_CONSOLE_PATHS = {
            "/login", "/error", "/css/**", "/js/**", "/webjars/**"};
    private static final String[] PUBLIC_ACTUATOR_PATHS = {"/actuator/health", "/actuator/health/**"};
    private static final String[] METRICS_PATHS = {
            "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**"};

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService factoryUserDetailsService(AuthProperties auth, PasswordEncoder encoder) {
        Map<String, String> declared = auth.parseUsers();
        if (declared.isEmpty()) {
            declared = Map.of(GENERATED_USERNAME, generatePassword(auth.enabled()));
        } else {
            // Usernames only, never passwords: a plain password containing the ','
            // separator would otherwise register an extra account silently, and this
            // line is the operator's chance to notice one they did not declare.
            log.info("factory.auth.users resolved {} account(s): {}", declared.size(), declared.keySet());
        }
        List<UserDetails> accounts = declared.entrySet().stream()
                .map(account -> User.withUsername(account.getKey())
                        .password(encodeIfPlain(encoder, account.getValue()))
                        .roles("USER")
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(accounts);
    }

    /**
     * Read-only REST. The console's own JavaScript (dashboard charts, execution page
     * polling) calls {@code /api/**} carrying nothing but the session cookie, which the
     * stateless write chain below cannot authenticate. Scoped to GET so nothing here
     * changes state — which is also why disabling CSRF on it opens no cross-site write.
     */
    @Bean
    @Order(0)
    @ConditionalOnBooleanProperty(name = "factory.auth.enabled", matchIfMissing = true)
    SecurityFilterChain apiReadSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                // CSRF is left ON but never applies: this chain matches GET alone, and
                // CsrfFilter exempts the safe methods. Disabling it would be dead config
                // that reads like a deliberate hole in a session-reading chain.
                // NEVER, not STATELESS: reuse an existing console session, never start one.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(1)
    @ConditionalOnBooleanProperty(name = "factory.auth.enabled", matchIfMissing = true)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .anyRequest().authenticated())
                // Nothing here is authenticated by an ambient cookie: the policy below
                // makes every request carry its own credentials, so there is no session
                // for a cross-site POST to ride, and a stateless client has no way to
                // obtain a token. Code scanning flags this rule generically; it is the
                // documented posture for stateless HTTP Basic.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(2)
    @ConditionalOnBooleanProperty(name = "factory.auth.enabled", matchIfMissing = true)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http, AuthProperties auth) throws Exception {
        return http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll();
                    if (auth.metricsPublic()) {
                        requests.requestMatchers(METRICS_PATHS).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                // Stateless Basic, same reasoning as the /api write chain.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(3)
    @ConditionalOnBooleanProperty(name = "factory.auth.enabled", matchIfMissing = true)
    SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_CONSOLE_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                // Basic alongside the session so curl and tests reach console pages
                // without driving the login form.
                .httpBasic(Customizer.withDefaults())
                // With both configured, the entry point would otherwise be chosen by the
                // Accept header — a browser gets the login page but anything without an
                // Accept header gets a bare 401. The console is browser-first, so always
                // send an unauthenticated caller to the form; wrong Basic credentials
                // still fail 401 from the filter itself.
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .build();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "factory.auth.enabled", havingValue = false)
    SecurityFilterChain permitAllSecurityFilterChain(HttpSecurity http) throws Exception {
        log.warn("factory.auth.enabled=false: the console and REST API are UNAUTHENTICATED and every "
                + "HITL decision is recorded as '{}'. Only safe behind an authenticating proxy.",
                CurrentUser.ANONYMOUS);
        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // No authentication at all on this chain, so there is no authority for a
                // forged request to borrow — CSRF protection would only break the same
                // callers permitAll() is here to let through.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    private static String generatePassword(boolean authEnabled) {
        String password = UUID.randomUUID().toString();
        if (authEnabled) {
            log.warn("""

                    Using generated security password for user '{}': {}

                    factory.auth.users (FACTORY_AUTH_USERS) is unset, so this password changes on every \
                    restart and every review decision is attributed to that one account. Configure real \
                    accounts before any shared deployment.
                    """, GENERATED_USERNAME, password);
        }
        return password;
    }

    private static String encodeIfPlain(PasswordEncoder encoder, String password) {
        // DelegatingPasswordEncoder reads a leading {id} as the encoding scheme, so a
        // value that already has one is stored as-is instead of being double-encoded.
        return password.startsWith("{") ? password : encoder.encode(password);
    }
}

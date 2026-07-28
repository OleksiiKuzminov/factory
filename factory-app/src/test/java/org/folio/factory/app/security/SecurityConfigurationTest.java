package org.folio.factory.app.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    /**
     * {@code ,} separates entries, so a plain password that contains one silently
     * yields an extra account. Nothing can disambiguate the parse, so the resolved
     * names are logged instead — and the passwords must never join them there.
     */
    @Test
    void resolvedUsernamesAreLoggedAtStartupWithoutTheirPasswords() {
        Logger securityLog = (Logger) LoggerFactory.getLogger(SecurityConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        securityLog.addAppender(appender);

        try {
            new SecurityConfiguration().factoryUserDetailsService(
                    new AuthProperties(true, "qa-lead:pw-one,rel-mgr:pw-two", false),
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
        } finally {
            securityLog.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("resolved 2 account(s)")
                .contains("qa-lead")
                .contains("rel-mgr")
                .doesNotContain("pw-one")
                .doesNotContain("pw-two"));
    }
}

package org.folio.factory.app;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * REL-2: a model call must not be able to hold an engine worker thread (and its step
 * lease) indefinitely.
 *
 * <p>Spring AI 2.0 gives each chat model its own OkHttp transport, so
 * {@code spring.http.clients} never reaches it, and its
 * {@code *HttpClientBuilderCustomizer} extension point is ineffective for timeouts —
 * {@code SpringAiAnthropicHttpClient.newCall} rebuilds the client per request from the
 * SDK's {@code ClientOptions.timeout}, overwriting all four legs. The single lever that
 * reaches the socket is therefore {@code spring.ai.<provider>.timeout}, which
 * {@code application.yaml} derives from {@code factory.llm.read-timeout}.</p>
 *
 * <p>These run the real {@code application.yaml} through the real Spring AI
 * autoconfiguration, and the last one proves the value on an actual socket.</p>
 */
class LlmClientTimeoutTest {

    /** The real application.yaml plus only the autoconfiguration that builds the model. */
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class, AnthropicChatAutoConfiguration.class))
            .withPropertyValues("spring.ai.anthropic.api-key=test-key");

    @Test
    void theShippedDefaultBoundsAModelCallWellInsideTheStepHeartbeatBudget() {
        contexts.run(context -> {
            assertThat(timeoutOf(context.getBean(AnthropicChatModel.class)))
                    .isEqualTo(Duration.ofSeconds(120));
            // Same knob for the OpenAI-compatible provider, which has its own transport.
            assertThat(openAiTimeout(context)).isEqualTo(Duration.ofSeconds(120));
        });
    }

    @Test
    void retimingTheReadTimeoutRetimesTheModelTheAppActuallyCallsWith() {
        contexts.withPropertyValues("factory.llm.read-timeout=45s").run(context -> {
            assertThat(timeoutOf(context.getBean(AnthropicChatModel.class)))
                    .isEqualTo(Duration.ofSeconds(45));
            assertThat(openAiTimeout(context)).isEqualTo(Duration.ofSeconds(45));
        });
    }

    @Test
    void aCompletionAgainstASilentServerGivesUpAtTheConfiguredTimeout() throws Exception {
        List<Socket> accepted = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread accepter = Thread.ofVirtual().start(() -> acceptAndSayNothing(silent, accepted));
            try {
                contexts.withPropertyValues(
                                "factory.llm.read-timeout=750ms",
                                "spring.ai.anthropic.base-url=http://127.0.0.1:" + silent.getLocalPort(),
                                // Otherwise the SDK spends the budget again on each retry.
                                "spring.ai.anthropic.max-retries=0")
                        .run(context -> {
                            AnthropicChatModel model = context.getBean(AnthropicChatModel.class);

                            long startedAt = System.nanoTime();
                            Throwable failure = catchThrowable(() -> model.call(new Prompt("ping")));
                            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

                            assertThat(failure).as("a silent endpoint must fail the step, not hang it")
                                    .isNotNull();
                            // The SDK's own default is 60s and its untouched OkHttp
                            // client-level default is 10 minutes; only the configured
                            // 750ms can land inside this bound.
                            assertThat(elapsed).as("gave up after %s", elapsed)
                                    .isLessThan(Duration.ofSeconds(20));
                        });
            } finally {
                accepter.interrupt();
            }
        }
        for (Socket socket : accepted) {
            socket.close();
        }
    }

    private static Duration timeoutOf(AnthropicChatModel model) {
        return ((AnthropicChatOptions) model.getDefaultOptions()).getTimeout();
    }

    /** Bound the way Spring AI binds it, so a value that is not a legal Duration fails here. */
    private static Duration openAiTimeout(ApplicationContext context) {
        return Binder.get(context.getEnvironment())
                .bind("spring.ai.openai.timeout", Bindable.of(Duration.class))
                .orElseThrow(() -> new AssertionError("spring.ai.openai.timeout is not set"));
    }

    private static void acceptAndSayNothing(ServerSocket server, List<Socket> accepted) {
        try {
            while (!server.isClosed()) {
                accepted.add(server.accept());
            }
        } catch (IOException closed) {
            // The test closed the server socket; nothing left to accept.
        }
    }
}

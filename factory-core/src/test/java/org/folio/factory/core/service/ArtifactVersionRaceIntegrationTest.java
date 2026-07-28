package org.folio.factory.core.service;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.PipelineExecution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version allocation under contention. Two writers of the same artifact — a duplicate
 * driver, or a QA amendment landing while a step writes — used to read the same
 * max(version) and collide on uq_artifact_version, losing one write.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class ArtifactVersionRaceIntegrationTest {

    private static final int WRITERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Test
    void concurrentWritersOfOneArtifactEachGetTheirOwnSequentialVersion() throws Exception {
        PipelineExecution execution = stateManager.createExecution("test-flow", "1.0.0", null);
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            List<Future<Artifact>> writes = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                String createdBy = "writer-" + writer;
                writes.add(pool.submit(() -> {
                    startLine.await();
                    return artifactStore.putMarkdown(execution.getId(), "contended.md", "from " + createdBy, createdBy);
                }));
            }
            startLine.countDown();

            List<Integer> versions = new ArrayList<>();
            for (Future<Artifact> write : writes) {
                versions.add(write.get(60, TimeUnit.SECONDS).getVersion());
            }

            assertThat(versions).containsExactlyInAnyOrderElementsOf(
                    IntStream.rangeClosed(1, WRITERS).boxed().toList());
            assertThat(artifactStore.getLatest(execution.getId(), "contended.md"))
                    .hasValueSatisfying(latest -> assertThat(latest.getVersion()).isEqualTo(WRITERS));
        } finally {
            pool.shutdownNow();
        }
    }
}

/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.athenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.github.dockerjava.api.exception.NotFoundException;
import com.yahoo.athenz.zms.ZMSClient;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.HttpStatus;

@EnabledIfDockerAvailable
class AthenzDockerTest {

    @TempDir
    Path tempDir;

    @Test
    void failedStartupRecreatesAndRemovesContainers() throws Exception {
        final File composeFile = failedStartupComposeFile();
        try (AthenzDocker docker = new AthenzDocker(composeFile)) {
            assertThat(docker.initialize()).isFalse();
            assertThat(docker.isInitialized()).isFalse();
            final List<String> attempts = Files.readAllLines(tempDir.resolve("state/attempts"));
            assertThat(attempts).hasSize(5);
            assertThat(attempts.stream().map(attempt -> attempt.split(" ")[0]))
                    .doesNotHaveDuplicates();
            for (String attempt : attempts) {
                final String containerId = attempt.split(" ")[1];
                assertThatThrownBy(() -> DockerClientFactory.instance().client()
                                                           .inspectContainerCmd(containerId).exec())
                        .isInstanceOf(NotFoundException.class);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void retriesScaffoldWithFreshClients() {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<ZMSClient> firstClient = new AtomicReference<>();
        final AtomicReference<URI> firstZtsUri = new AtomicReference<>();
        try (AthenzDocker docker = new AthenzDocker(new File("src/test/resources/docker/docker-compose.yml")) {
            @Override
            protected void scaffold(ZMSClient zmsClient) {
                if (attempts.incrementAndGet() == 1) {
                    firstClient.set(zmsClient);
                    firstZtsUri.set(ztsUri());
                    throw new IllegalStateException("Fail the first scaffold attempt");
                }
                assertThat(zmsClient).isNotSameAs(firstClient.get());
            }
        }) {
            assertThat(docker.initialize()).isTrue();
            assertThat(docker.isInitialized()).isTrue();
            assertThat(attempts).hasValue(2);
            assertThat(docker.ztsUri()).isNotSameAs(firstZtsUri.get());
            try (ZtsBaseClient client = docker.newZtsBaseClient(AthenzDocker.TEST_SERVICE)) {
                assertThat(client.webClient().get("/status").aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
            }
            assertThat(docker.initialize()).isTrue();
            assertThat(attempts).hasValue(2);
        }
    }

    @Test
    void cleanupFailureStopsRetries() throws Exception {
        final AtomicInteger cleanups = new AtomicInteger();
        try (AthenzDocker docker = new AthenzDocker(failedStartupComposeFile()) {
            @Override
            public void close() {
                super.close();
                if (cleanups.incrementAndGet() == 1) {
                    throw new IllegalStateException("Fail the first cleanup attempt");
                }
            }
        }) {
            assertThat(docker.initialize()).isFalse();
            assertThat(cleanups).hasValue(1);
            assertThat(Files.readAllLines(tempDir.resolve("state/attempts"))).hasSize(1);
        }
    }

    private File failedStartupComposeFile() throws IOException {
        final Path stateDir = Files.createDirectory(tempDir.resolve("state"));
        final Path composeFile = tempDir.resolve("docker-compose.yml");
        Files.writeString(composeFile,
                          "services:\n" +
                          "  zms-server:\n" +
                          "    image: alpine:3.22\n" +
                          "    init: true\n" +
                          "    command: [sh, -c, 'echo $$PROJECT $$HOSTNAME >> /state/attempts; " +
                          "exec sleep infinity']\n" +
                          "    environment:\n" +
                          "      PROJECT: ${COMPOSE_PROJECT_NAME}\n" +
                          "    volumes:\n" +
                          "      - '" + stateDir.toAbsolutePath() + ":/state'\n" +
                          "    healthcheck:\n" +
                          "      test: [CMD, 'false']\n" +
                          "      interval: 1s\n" +
                          "      timeout: 1s\n" +
                          "      retries: 1\n" +
                          "  zts-server:\n" +
                          "    image: alpine:3.22\n" +
                          "    init: true\n" +
                          "    command: [sleep, infinity]\n" +
                          "    depends_on:\n" +
                          "      zms-server:\n" +
                          "        condition: service_healthy\n",
                          StandardCharsets.UTF_8);

        return composeFile.toFile();
    }
}

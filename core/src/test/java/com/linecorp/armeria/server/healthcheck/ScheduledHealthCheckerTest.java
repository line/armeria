/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the Licenses
 */

package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

class ScheduledHealthCheckerTest {

    @Test
    void usedByMultipleServers() {
        final ScheduledHealthChecker alwaysHealth =
                (ScheduledHealthChecker) HealthChecker.of(
                        () -> CompletableFuture.completedFuture(new HealthCheckStatus(true, 100)),
                        Duration.ofDays(10));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(alwaysHealth))
                      .build();
        server.start().join();
        assertThat(alwaysHealth.isActive()).isTrue();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(1);

        final Server server2 =
                Server.builder()
                      .service("/hc", HealthCheckService.of(alwaysHealth))
                      .build();
        server2.start().join();
        assertThat(alwaysHealth.isActive()).isTrue();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(2);

        server.stop().join();
        assertThat(alwaysHealth.isActive()).isTrue();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(1);

        server2.stop().join();
        assertThat(alwaysHealth.isActive()).isFalse();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(0);
    }

    @Test
    void usedByMultipleServers_scheduleAgain() {
        final ScheduledHealthChecker alwaysHealth =
                (ScheduledHealthChecker) HealthChecker.of(
                        () -> CompletableFuture.completedFuture(new HealthCheckStatus(true, 100)),
                        Duration.ofDays(10));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(alwaysHealth))
                      .build();
        server.start().join();
        server.stop().join();
        assertThat(alwaysHealth.isActive()).isFalse();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(0);

        final Server server2 =
                Server.builder()
                      .service("/hc", HealthCheckService.of(alwaysHealth))
                      .build();
        server2.start().join();
        assertThat(alwaysHealth.isActive()).isTrue();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(1);

        server2.stop().join();
        assertThat(alwaysHealth.isActive()).isFalse();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(0);
    }

    @Test
    void usedByMultipleService() {
        final ScheduledHealthChecker alwaysHealth =
                (ScheduledHealthChecker) HealthChecker.of(
                        () -> CompletableFuture.completedFuture(new HealthCheckStatus(true, 100)),
                        Duration.ofDays(10));
        final Server server =
                Server.builder()
                      .service("/hc1", HealthCheckService.of(alwaysHealth))
                      .service("/hc2", HealthCheckService.of(alwaysHealth))
                      .build();
        server.start().join();
        assertThat(alwaysHealth.isActive()).isTrue();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(2);

        server.stop().join();
        assertThat(alwaysHealth.isActive()).isFalse();
        assertThat(alwaysHealth.getRequestCount()).isEqualTo(0);
    }

    @Test
    void awareUnhealthy() {
        final AtomicBoolean health = new AtomicBoolean(true);
        final ScheduledHealthChecker healthChecker =
                (ScheduledHealthChecker) HealthChecker.of(
                        () -> CompletableFuture.completedFuture(new HealthCheckStatus(health.get(), 100)),
                        Duration.ofDays(10));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();
        server.start().join();

        final String uri = "http://localhost:" + server.activeLocalPort();
        assertThat(WebClient.of(uri).get("/hc").aggregate().join().status()).isSameAs(HttpStatus.OK);

        health.set(false);
        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(
                       () -> assertThat(WebClient.of(uri).get("/hc").aggregate().join().status())
                               .isSameAs(HttpStatus.SERVICE_UNAVAILABLE));
        server.stop();
    }
}

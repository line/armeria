/*
 * Copyright 2022 LINE Corporation
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
 * under the License.
 */

package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

class ScheduledHealthCheckerTest {
    @Test
    void stopSchedulingAfterStop() throws InterruptedException {
        final AtomicInteger invokedCount = new AtomicInteger();
        final AtomicReference<CompletableFuture<HealthCheckStatus>> holder = new AtomicReference<>();
        final ScheduledHealthChecker healthChecker =
                (ScheduledHealthChecker) HealthChecker.of(() -> {
                    invokedCount.incrementAndGet();
                    final CompletableFuture<HealthCheckStatus> healthCheckFuture = new CompletableFuture<>();
                    holder.set(healthCheckFuture);
                    return healthCheckFuture;
                }, Duration.ofHours(1));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();

        assertThat(invokedCount.get()).isZero();

        server.start().join();
        await().untilAsserted(() -> assertThat(invokedCount.get()).isOne());

        holder.get().complete(new HealthCheckStatus(true, 100));
        await().untilAsserted(() -> assertThat(invokedCount.get()).isEqualTo(2));

        server.stop().join();
        holder.get().complete(new HealthCheckStatus(true, 100));
        // Wait for a while to verify health checker is not invoked anymore.
        Thread.sleep(1000);
        assertThat(invokedCount.get()).isEqualTo(2);
    }

    @Test
    void usedByMultipleServers() {
        final ScheduledHealthChecker healthChecker =
                (ScheduledHealthChecker) HealthChecker.of(CompletableFuture::new, Duration.ofHours(1));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();
        server.start().join();
        assertThat(healthChecker.isActive()).isTrue();
        assertThat(healthChecker.getRequestCount()).isEqualTo(1);

        final Server server2 =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();
        server2.start().join();
        assertThat(healthChecker.isActive()).isTrue();
        assertThat(healthChecker.getRequestCount()).isEqualTo(2);

        server.stop().join();
        assertThat(healthChecker.isActive()).isTrue();
        assertThat(healthChecker.getRequestCount()).isEqualTo(1);

        server2.stop().join();
        assertThat(healthChecker.isActive()).isFalse();
        assertThat(healthChecker.getRequestCount()).isEqualTo(0);
    }

    @Test
    void usedByMultipleServers_scheduleAgain() {
        final ScheduledHealthChecker healthChecker =
                (ScheduledHealthChecker) HealthChecker.of(CompletableFuture::new, Duration.ofHours(1));
        final Server server =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();
        server.start().join();
        server.stop().join();
        assertThat(healthChecker.isActive()).isFalse();
        assertThat(healthChecker.getRequestCount()).isEqualTo(0);

        final Server server2 =
                Server.builder()
                      .service("/hc", HealthCheckService.of(healthChecker))
                      .build();
        server2.start().join();
        assertThat(healthChecker.isActive()).isTrue();
        assertThat(healthChecker.getRequestCount()).isEqualTo(1);

        server2.stop().join();
        assertThat(healthChecker.isActive()).isFalse();
        assertThat(healthChecker.getRequestCount()).isEqualTo(0);
    }

    @Test
    void usedByMultipleService() {
        final ScheduledHealthChecker healthChecker =
                (ScheduledHealthChecker) HealthChecker.of(CompletableFuture::new, Duration.ofHours(1));
        final Server server =
                Server.builder()
                      .service("/hc1", HealthCheckService.of(healthChecker))
                      .service("/hc2", HealthCheckService.of(healthChecker))
                      .build();
        server.start().join();
        assertThat(healthChecker.isActive()).isTrue();
        assertThat(healthChecker.getRequestCount()).isEqualTo(2);

        server.stop().join();
        assertThat(healthChecker.isActive()).isFalse();
        assertThat(healthChecker.getRequestCount()).isEqualTo(0);
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
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(
                       () -> assertThat(WebClient.of(uri).get("/hc").aggregate().join().status())
                               .isSameAs(HttpStatus.SERVICE_UNAVAILABLE));
        server.stop();
    }
}

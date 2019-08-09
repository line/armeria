/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.SettableHealthChecker;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpHealthCheckedEndpointGroupLongPollingTest {

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(3);
    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private static final SettableHealthChecker health = new SettableHealthChecker();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(HEALTH_CHECK_PATH, HealthCheckService.of(health));

            // Enable graceful shutdown so that the server is given a chance
            // to send a health check response when the server is shutting down.
            // Without graceful shutdown, the health check request will be aborted
            // with GOAWAY or disconnection.
            sb.gracefulShutdownTimeout(3000, 10000);
        }
    };

    @Nullable
    private volatile BlockingQueue<RequestLog> healthCheckRequestLogs;

    @BeforeEach
    void startServer() {
        server.start();
    }

    @Test
    void immediateNotification() throws Exception {
        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        new StaticEndpointGroup(endpoint),
                        HEALTH_CHECK_PATH))) {

            // Check the initial state (healthy).
            assertThat(endpointGroup.endpoints()).containsExactly(endpoint);

            // Make the server unhealthy.
            health.setHealthy(false);
            waitForGroup(endpointGroup, null);

            // Make the server healthy again.
            health.setHealthy(true);
            waitForGroup(endpointGroup, endpoint);

            // Stop the server.
            server.stop();
            waitForGroup(endpointGroup, null);
        }
    }

    @Test
    @Timeout(15)
    void longPollingDisabledOnStop() throws Exception {
        final BlockingQueue<RequestLog> healthCheckRequestLogs = new LinkedTransferQueue<>();
        this.healthCheckRequestLogs = healthCheckRequestLogs;
        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        new StaticEndpointGroup(endpoint),
                        HEALTH_CHECK_PATH))) {

            // Check the initial state (healthy).
            assertThat(endpointGroup.endpoints()).containsExactly(endpoint);

            // Drop the first request.
            healthCheckRequestLogs.take();

            // Stop the server.
            server.stop();
            waitForGroup(endpointGroup, null);

            // Must receive the '503 Service Unavailable' response with long polling disabled,
            // so that the next health check respects the backoff.
            final ResponseHeaders stoppingResponseHeaders = healthCheckRequestLogs.take().responseHeaders();
            assertThat(stoppingResponseHeaders.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(stoppingResponseHeaders.getLong("armeria-lphc", -1)).isEqualTo(0);

            // Check the next check respected backoff, because there's no point of
            // sending a request immediately only to get a 'connection refused' error.
            final Stopwatch stopwatch = Stopwatch.createStarted();
            healthCheckRequestLogs.take();
            assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS))
                    .isGreaterThan(RETRY_INTERVAL.toMillis() * 4 / 5);
        } finally {
            this.healthCheckRequestLogs = null;
        }
    }

    @Test
    @Timeout(15)
    void periodicCheckWhenConnectionRefused() throws Exception {
        final BlockingQueue<RequestLog> healthCheckRequestLogs = new LinkedTransferQueue<>();
        this.healthCheckRequestLogs = healthCheckRequestLogs;
        final Endpoint endpoint = Endpoint.of("127.0.0.1", 1);
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(
                        new StaticEndpointGroup(endpoint),
                        HEALTH_CHECK_PATH))) {

            // Check the initial state (unhealthy).
            assertThat(endpointGroup.endpoints()).isEmpty();

            // Drop the first request.
            healthCheckRequestLogs.take();

            final Stopwatch stopwatch = Stopwatch.createUnstarted();
            for (int i = 0; i < 2; i++) {
                stopwatch.reset().start();
                healthCheckRequestLogs.take();
                assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS))
                        .isGreaterThan(RETRY_INTERVAL.toMillis() * 4 / 5);
            }
        } finally {
            this.healthCheckRequestLogs = null;
        }
    }

    /**
     * Makes sure the notification occurs as soon as possible thanks to long polling.
     */
    private static void waitForGroup(EndpointGroup group, @Nullable Endpoint expectedEndpoint) {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        if (expectedEndpoint != null) {
            await().untilAsserted(() -> assertThat(group.endpoints()).containsExactly(expectedEndpoint));
        } else {
            await().untilAsserted(() -> assertThat(group.endpoints()).isEmpty());
        }
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isLessThan(RETRY_INTERVAL.toMillis() / 3);
    }

    private HealthCheckedEndpointGroup build(HealthCheckedEndpointGroupBuilder builder) {
        // Specify backoff explicitly to disable jitter.
        builder.retryBackoff(Backoff.fixed(RETRY_INTERVAL.toMillis()));
        builder.withClientOptions(b -> {
            b.decorator(LoggingClient.newDecorator());
            b.decorator((delegate, ctx, req) -> {
                // Record when health check requests were sent.
                final Queue<RequestLog> healthCheckRequestLogs = this.healthCheckRequestLogs;
                if (healthCheckRequestLogs != null) {
                    healthCheckRequestLogs.add(ctx.log());
                }
                return delegate.execute(ctx, req);
            });
            return b;
        });
        return builder.build();
    }
}

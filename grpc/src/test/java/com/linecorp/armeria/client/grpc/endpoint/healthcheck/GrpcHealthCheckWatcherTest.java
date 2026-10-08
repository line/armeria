/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.client.grpc.endpoint.healthcheck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.HealthGrpcServerExtension;

import io.grpc.health.v1.HealthCheckResponse;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcHealthCheckWatcherTest {

    private static final long NEXT_DELAY_MILLIS = 500L;

    @RegisterExtension
    private static HealthGrpcServerExtension serverExtension = new HealthGrpcServerExtension();

    // Captured once, before any test stops the shared serverExtension - serverExtension.endpoint()
    // can't be called again once the server has been stopped.
    private static Endpoint endpoint;

    @Mock
    private HealthCheckerContext context;

    @Mock
    private ScheduledExecutorService executor;

    private GrpcHealthCheckWatcher healthCheckWatcher;

    @BeforeAll
    static void beforeAll() {
        endpoint = serverExtension.endpoint(SessionProtocol.H2C);
    }

    @BeforeEach
    void setUp() {
        when(context.clientOptions())
                .thenReturn(ClientOptions.builder().responseTimeout(Duration.ofMillis(500)).build());

        lenient().when(context.executor()).thenReturn(executor);
        lenient().when(context.nextDelayMillis()).thenReturn(NEXT_DELAY_MILLIS);

        healthCheckWatcher = new GrpcHealthCheckWatcher(context, endpoint, SessionProtocol.H2C, null);
    }

    @AfterEach
    void tearDown() {
        healthCheckWatcher.close();
    }

    @Test
    @Order(1)
    void healthy() {
        serverExtension.setStatus(HealthCheckResponse.ServingStatus.SERVING);

        healthCheckWatcher.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.HEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));
    }

    @Test
    @Order(2)
    void unhealthy() {
        serverExtension.setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING);

        healthCheckWatcher.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));
    }

    @Test
    @Order(3)
    void unhealthyThenHealthy() {
        serverExtension.setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING);

        healthCheckWatcher.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));

        serverExtension.setStatus(HealthCheckResponse.ServingStatus.SERVING);

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.HEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));
    }

    /**
     * These last two tests permanently take down the shared {@link #serverExtension}, so they must run
     * last and in this order - {@link TestMethodOrder} pins that down explicitly rather than relying on
     * incidental method declaration order.
     */
    @Test
    @Order(4)
    void reconnectsImmediatelyWhenMessageWasReceived() {
        serverExtension.setStatus(HealthCheckResponse.ServingStatus.SERVING);

        healthCheckWatcher.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.HEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));

        // Kill the connection now that a message has been received; the watcher should reconnect
        // immediately instead of backing off, since the server was reachable moments ago.
        serverExtension.stop().join();

        verify(executor, timeout(1000)).execute(any(Runnable.class));
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    @Order(5)
    void reconnectsWithBackoffWhenNoMessageReceived() {
        serverExtension.stop().join();

        healthCheckWatcher.check();

        verify(executor, timeout(1000))
                .schedule(any(Runnable.class), eq(NEXT_DELAY_MILLIS), eq(TimeUnit.MILLISECONDS));
        verify(executor, never()).execute(any(Runnable.class));
    }
}

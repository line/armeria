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
package com.linecorp.armeria.internal.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.HealthGrpcServerExtension;

import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse;

@ExtendWith(MockitoExtension.class)
class GrpcHealthCheckerTest {

    private static final long NEXT_DELAY_MILLIS = 500L;

    @RegisterExtension
    private static HealthGrpcServerExtension serverExtension = new HealthGrpcServerExtension();

    @Mock
    private HealthCheckerContext context;

    @Mock
    private ScheduledExecutorService executor;

    @Captor
    private ArgumentCaptor<Throwable> throwableArgumentCaptor;

    private GrpcHealthChecker healthChecker;

    @BeforeEach
    void setUp() {
        when(context.clientOptions())
                .thenReturn(ClientOptions.builder().responseTimeout(Duration.ofMillis(500)).build());

        when(context.executor()).thenReturn(executor);

        healthChecker = new GrpcHealthChecker(context, serverExtension.endpoint(SessionProtocol.H2C),
                SessionProtocol.H2C, null);
    }

    @AfterEach
    void tearDown() {
        healthChecker.close();
    }

    @Test
    void healthy() {
        when(context.nextDelayMillis()).thenReturn(NEXT_DELAY_MILLIS);

        serverExtension.setStatus(HealthCheckResponse.ServingStatus.SERVING);

        healthChecker.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.HEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));

        verify(executor).schedule(any(Runnable.class), eq(NEXT_DELAY_MILLIS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void unhealthy() {
        serverExtension.setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING);

        healthChecker.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), eq(null));

        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void exception() {
        serverExtension.stop().join();

        healthChecker.check();

        verify(context, timeout(1000)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), isNull(), throwableArgumentCaptor.capture());

        verify(executor).execute(any(Runnable.class));

        final Throwable exception = throwableArgumentCaptor.getValue();
        assertThat(exception).isInstanceOf(StatusRuntimeException.class)
                .hasMessageStartingWith("UNAVAILABLE");
    }
}

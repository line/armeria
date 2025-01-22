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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

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

@ExtendWith(MockitoExtension.class)
class GrpcHealthCheckerTest {

    @RegisterExtension
    private static HealthGrpcServerExtension serverExtension = new HealthGrpcServerExtension();

    @Mock
    private HealthCheckerContext context;

    @Captor
    private ArgumentCaptor<Throwable> throwableArgumentCaptor;

    private GrpcHealthChecker healthChecker;

    @BeforeEach
    void setUp() {
        when(context.clientOptions())
                .thenReturn(ClientOptions.builder().responseTimeout(Duration.ofMillis(500)).build());

        healthChecker = new GrpcHealthChecker(context, serverExtension.endpoint(SessionProtocol.H2C),
                SessionProtocol.H2C, null);
    }

    @Test
    void healthy() {
        serverExtension.setAction(HealthGrpcServerExtension.Action.DO_HEALTHY);

        healthChecker.check();

        verify(context, timeout(1000).times(1)).updateHealth(eq(GrpcHealthChecker.HEALTHY),
                any(ClientRequestContext.class), eq(null), eq(null));
    }

    @Test
    void unhealthy() {
        serverExtension.setAction(HealthGrpcServerExtension.Action.DO_UNHEALTHY);

        healthChecker.check();

        verify(context, timeout(1000).times(1)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), eq(null), eq(null));
    }

    @Test
    void exception() {
        serverExtension.setAction(HealthGrpcServerExtension.Action.DO_TIMEOUT);

        healthChecker.check();

        verify(context, timeout(1000).times(1)).updateHealth(eq(GrpcHealthChecker.UNHEALTHY),
                any(ClientRequestContext.class), any(ResponseHeaders.class), throwableArgumentCaptor.capture());

        final Throwable exception = throwableArgumentCaptor.getValue();
        assertThat(exception).isInstanceOf(StatusRuntimeException.class)
                .hasMessageStartingWith("DEADLINE_EXCEEDED");
    }
}

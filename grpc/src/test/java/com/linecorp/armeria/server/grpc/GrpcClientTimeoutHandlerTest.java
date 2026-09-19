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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcClientTimeoutHandlerTest {

    private static final ServerMethodDefinition<?, ?> METHOD =
            new TestServiceImplBase() {}.bindService()
                                        .getMethod(TestServiceGrpc.getUnaryCallMethod().getFullMethodName());

    @Test
    void enabled() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled();
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        // An infinite client timeout stays infinite.
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.disabled();
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isNull();
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ZERO)).isNull();
    }

    @Test
    void boundedByServerTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.boundedByServerTimeout();
        // A timeout longer than the server timeout is capped.
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofMillis(2000));
        // A timeout shorter than the server timeout is kept as it is.
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(500));
        // An infinite client timeout is capped as well.
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ZERO)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void boundedByServerTimeoutWhenServerHasNoTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.boundedByServerTimeout();
        assertThat(handler.apply(ctx(0), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(handler.apply(ctx(0), METHOD, Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void withBuffer() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.withBuffer(Duration.ofSeconds(1));
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(31));
        // An infinite client timeout is left alone.
        assertThat(handler.apply(ctx(2000), METHOD, Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void withBufferDoesNotOverflow() {
        // 'grpc-timeout' saturates at Long.MAX_VALUE nanoseconds, e.g. for '99999999H'.
        final Duration maxTimeout = Duration.ofNanos(Long.MAX_VALUE);
        assertThat(TimeoutHeaderUtil.fromHeaderValue("99999999H")).isEqualTo(Long.MAX_VALUE);
        assertThat(GrpcClientTimeoutHandler.withBuffer(Duration.ofSeconds(1))
                                           .apply(ctx(2000), METHOD, maxTimeout))
                .isEqualTo(maxTimeout);
        assertThat(GrpcClientTimeoutHandler.withBuffer(Duration.ofSeconds(Long.MAX_VALUE))
                                           .apply(ctx(2000), METHOD, Duration.ofSeconds(1)))
                .isEqualTo(maxTimeout);
    }

    @Test
    void withZeroBuffer() {
        assertThat(GrpcClientTimeoutHandler.withBuffer(Duration.ZERO))
                .isSameAs(GrpcClientTimeoutHandler.enabled());
    }

    @Test
    void withNegativeBuffer() {
        assertThatThrownBy(() -> GrpcClientTimeoutHandler.withBuffer(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buffer");
    }

    private static ServiceRequestContext ctx(long serverTimeoutMillis) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                    .serverConfigurator(sb -> sb.requestTimeoutMillis(serverTimeoutMillis))
                                    .build();
    }
}

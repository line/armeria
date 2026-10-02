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

class GrpcTimeoutPolicyTest {

    private static final ServerMethodDefinition<?, ?> METHOD =
            new TestServiceImplBase() {}.bindService()
                                        .getMethod(TestServiceGrpc.getUnaryCallMethod().getFullMethodName());

    @Test
    void useGrpcTimeoutHeader() {
        final GrpcTimeoutPolicy policy = GrpcTimeoutPolicy.useGrpcTimeoutHeader();
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        // An absent header stays infinite.
        assertThat(policy.apply(ctx(2000), METHOD, null)).isNull();
    }

    @Test
    void useGrpcTimeoutHeaderWithMax() {
        final GrpcTimeoutPolicy policy = GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10));
        // A timeout longer than the max is capped.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(10));
        // A shorter timeout is kept as it is.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(500));
        // An absent header is capped as well, so that omitting it does not yield an infinite timeout.
        assertThat(policy.apply(ctx(2000), METHOD, null)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void useGrpcTimeoutHeaderWithNonPositiveMax() {
        assertThatThrownBy(() -> GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
        assertThatThrownBy(() -> GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
    }

    @Test
    void useServiceTimeout() {
        final GrpcTimeoutPolicy policy = GrpcTimeoutPolicy.useServiceTimeout();
        // A negative duration keeps whatever the context carries, which is the timeout configured for the
        // service unless a decorator changed it.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isNegative();
        // A shorter client timeout is ignored too.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofMillis(500))).isNegative();
        assertThat(policy.apply(ctx(2000), METHOD, null)).isNegative();
        assertThat(policy.apply(ctx(0), METHOD, Duration.ofSeconds(30))).isNegative();
    }

    @Test
    void withNegativeOffset() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10), Duration.ofMillis(-500));
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(4500));
        // An absent header falls back to the max, which is shifted as well.
        assertThat(policy.apply(ctx(2000), METHOD, null)).isEqualTo(Duration.ofMillis(9500));
    }

    @Test
    void negativeOffsetCanExhaustTheDeadline() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10), Duration.ofSeconds(-5));
        // 5s minus 5s must not be mistaken for an infinite timeout.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(5))).isZero();
        // Nor may an over-shortened deadline come out negative, which would keep the current timeout instead
        // of failing the request.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(3))).isZero();
    }

    @Test
    void withPositiveOffset() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(31));
        assertThat(policy.apply(ctx(2000), METHOD, null)).isEqualTo(Duration.ofSeconds(61));
    }

    @Test
    void positiveOffsetDoesNotOverflow() {
        // 'grpc-timeout' saturates at Long.MAX_VALUE nanoseconds, e.g. for '99999999H'.
        final Duration maxTimeout = Duration.ofNanos(Long.MAX_VALUE);
        assertThat(TimeoutHeaderUtil.fromHeaderValue("99999999H")).isEqualTo(Long.MAX_VALUE);
        assertThat(GrpcTimeoutPolicy.useGrpcTimeoutHeader(maxTimeout, Duration.ofSeconds(1))
                                    .apply(ctx(2000), METHOD, maxTimeout))
                .isEqualTo(maxTimeout);
        assertThat(GrpcTimeoutPolicy.useGrpcTimeoutHeader(maxTimeout,
                                                          Duration.ofSeconds(Long.MAX_VALUE))
                                    .apply(ctx(2000), METHOD, Duration.ofSeconds(1)))
                .isEqualTo(maxTimeout);
    }

    @Test
    void withZeroOffset() {
        assertThat(GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10), Duration.ZERO))
                .hasToString(GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10)).toString());
    }

    private static ServiceRequestContext ctx(long serviceTimeoutMillis) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                    .serverConfigurator(sb -> sb.requestTimeoutMillis(serviceTimeoutMillis))
                                    .build();
    }
}

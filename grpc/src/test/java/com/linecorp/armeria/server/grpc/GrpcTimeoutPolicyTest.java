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
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofMillis(2000));
        // A shorter client timeout is ignored too.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(2000));
        assertThat(policy.apply(ctx(2000), METHOD, null)).isEqualTo(Duration.ofMillis(2000));
        // A service without a timeout means no timeout.
        assertThat(policy.apply(ctx(0), METHOD, Duration.ofSeconds(30))).isNull();
    }

    @Test
    void useServiceTimeoutIgnoresOffset() {
        assertThat(GrpcTimeoutPolicy.useServiceTimeout().withOffset(Duration.ofSeconds(-1)))
                .isSameAs(GrpcTimeoutPolicy.useServiceTimeout());
    }

    @Test
    void withNegativeOffset() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader().withOffset(Duration.ofMillis(-500));
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(4500));
        // An infinite timeout is left alone.
        assertThat(policy.apply(ctx(2000), METHOD, null)).isNull();
    }

    @Test
    void negativeOffsetCanExhaustTheDeadline() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader().withOffset(Duration.ofSeconds(-5));
        // 5s minus 5s must not be mistaken for an infinite timeout.
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(5))).isZero();
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(3))).isNegative();
    }

    @Test
    void withPositiveOffset() {
        final GrpcTimeoutPolicy policy =
                GrpcTimeoutPolicy.useGrpcTimeoutHeader().withOffset(Duration.ofSeconds(1));
        assertThat(policy.apply(ctx(2000), METHOD, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(31));
        assertThat(policy.apply(ctx(2000), METHOD, null)).isNull();
    }

    @Test
    void positiveOffsetDoesNotOverflow() {
        // 'grpc-timeout' saturates at Long.MAX_VALUE nanoseconds, e.g. for '99999999H'.
        final Duration maxTimeout = Duration.ofNanos(Long.MAX_VALUE);
        assertThat(TimeoutHeaderUtil.fromHeaderValue("99999999H")).isEqualTo(Long.MAX_VALUE);
        assertThat(GrpcTimeoutPolicy.useGrpcTimeoutHeader()
                                    .withOffset(Duration.ofSeconds(1))
                                    .apply(ctx(2000), METHOD, maxTimeout))
                .isEqualTo(maxTimeout);
        assertThat(GrpcTimeoutPolicy.useGrpcTimeoutHeader()
                                    .withOffset(Duration.ofSeconds(Long.MAX_VALUE))
                                    .apply(ctx(2000), METHOD, Duration.ofSeconds(1)))
                .isEqualTo(maxTimeout);
    }

    @Test
    void withZeroOffset() {
        final GrpcTimeoutPolicy policy = GrpcTimeoutPolicy.useGrpcTimeoutHeader();
        assertThat(policy.withOffset(Duration.ZERO)).isSameAs(policy);
    }

    @Test
    void offsetAppliesToACustomPolicy() {
        final GrpcTimeoutPolicy policy =
                (ctx, method, clientTimeout) -> Duration.ofSeconds(3);
        assertThat(policy.withOffset(Duration.ofSeconds(-1)).apply(ctx(2000), METHOD, null))
                .isEqualTo(Duration.ofSeconds(2));
    }

    private static ServiceRequestContext ctx(long serviceTimeoutMillis) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                    .serverConfigurator(sb -> sb.requestTimeoutMillis(serviceTimeoutMillis))
                                    .build();
    }
}

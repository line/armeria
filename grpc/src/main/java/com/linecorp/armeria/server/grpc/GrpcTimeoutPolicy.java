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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;

/**
 * Decides the request timeout to use for the timeout a client requested via the {@code grpc-timeout}
 * header.
 *
 * <p>This allows a server to adjust or reject the timeout a client asked for, e.g. to make sure that an
 * untrusted client cannot ask for an arbitrarily long timeout:
 * <pre>{@code
 * GrpcService.builder()
 *            .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10)))
 *            .build();
 * }</pre>
 *
 * @see GrpcServiceBuilder#timeoutPolicy(GrpcTimeoutPolicy)
 */
@UnstableApi
@FunctionalInterface
public interface GrpcTimeoutPolicy {

    /**
     * Returns a {@link GrpcTimeoutPolicy} that uses the timeout a client requested as it is.
     * This is the default behavior.
     */
    static GrpcTimeoutPolicy useGrpcTimeoutHeader() {
        return GrpcTimeoutPolicies.USE_GRPC_TIMEOUT_HEADER;
    }

    /**
     * Returns a {@link GrpcTimeoutPolicy} that uses the timeout a client requested, but never more than the
     * specified {@code max}. A client that asks for a longer timeout, or for no timeout at all, gets
     * {@code max} instead.
     *
     * <p>Note that a client is still free to ask for a shorter timeout, which is used as it is.
     *
     * @throws IllegalArgumentException if the {@code max} is zero or negative
     */
    static GrpcTimeoutPolicy useGrpcTimeoutHeader(Duration max) {
        return useGrpcTimeoutHeader(max, Duration.ZERO);
    }

    /**
     * Returns a {@link GrpcTimeoutPolicy} that uses the timeout a client requested bounded by the specified
     * {@code max}, and then shifts it by the specified {@code offset}.
     *
     * <p>A negative {@code offset} shortens the timeout, which lets a server respond with
     * {@code DEADLINE_EXCEEDED} before the client gives up, rather than seeing the call cancelled:
     * <pre>{@code
     * GrpcTimeoutPolicy.useGrpcTimeoutHeader(Duration.ofSeconds(10), Duration.ofMillis(-500));
     * }</pre>
     *
     * <p>If the {@code offset} shortens a timeout to zero or less, the request fails immediately.
     *
     * @throws IllegalArgumentException if the {@code max} is zero or negative
     */
    static GrpcTimeoutPolicy useGrpcTimeoutHeader(Duration max, Duration offset) {
        requireNonNull(max, "max");
        requireNonNull(offset, "offset");
        if (max.isZero() || max.isNegative()) {
            throw new IllegalArgumentException("max: " + max + " (expected: > 0)");
        }
        return new GrpcTimeoutPolicies.Bounded(max, offset.isZero() ? null : offset);
    }

    /**
     * Returns a {@link GrpcTimeoutPolicy} that ignores the {@code grpc-timeout} header, even if a client asks
     * for a shorter timeout, so that the request timeout configured for the Armeria server is always used,
     * e.g. the one set via {@link ServerBuilder#requestTimeout(Duration)}.
     *
     * <p>Note that this leaves the request timeout of a {@link ServiceRequestContext} untouched rather than
     * setting it again, so a timeout a decorator set is kept as it is.
     */
    static GrpcTimeoutPolicy useServiceTimeout() {
        return GrpcTimeoutPolicies.USE_SERVICE_TIMEOUT;
    }

    /**
     * Returns the request timeout to set for the specified {@link ServiceRequestContext}.
     *
     * @param ctx the {@link ServiceRequestContext} of the request
     * @param method the {@link ServerMethodDefinition} the request is routed to
     * @param clientTimeout the timeout requested via the {@code grpc-timeout} header, or {@code null} if a
     *                      client asked for no timeout, either by omitting the header, which the gRPC
     *                      specification defines as an infinite timeout, or by sending a zero timeout
     *
     * @return the timeout to use, or {@code null} to use an infinite timeout. {@link Duration#ZERO} fails the
     *         request immediately with {@code DEADLINE_EXCEEDED}, and a negative {@link Duration} leaves the
     *         request timeout of the {@link ServiceRequestContext} as it is, which is how you keep a timeout a
     *         decorator set.
     */
    @Nullable
    Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                   @Nullable Duration clientTimeout);
}

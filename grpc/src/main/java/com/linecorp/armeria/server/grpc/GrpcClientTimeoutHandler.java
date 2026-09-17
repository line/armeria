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

/**
 * Decides the request timeout to use for a request that carries a {@code grpc-timeout} header.
 *
 * <p>This allows a server to adjust or reject the timeout requested by a client, e.g. to make sure that
 * an untrusted client cannot ask for an arbitrarily long timeout:
 * <pre>{@code
 * GrpcService.builder()
 *            .clientTimeoutHandler(GrpcClientTimeoutHandler.boundedByServerTimeout())
 *            .build();
 * }</pre>
 *
 * @see GrpcServiceBuilder#clientTimeoutHandler(GrpcClientTimeoutHandler)
 */
@UnstableApi
@FunctionalInterface
public interface GrpcClientTimeoutHandler {

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that uses the timeout requested by the client as it is.
     * This is the default behavior.
     */
    static GrpcClientTimeoutHandler enabled() {
        return GrpcClientTimeoutHandlers.ENABLED;
    }

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that ignores the {@code grpc-timeout} header, so that the
     * request timeout configured for the Armeria server is always used, e.g. the one set via
     * {@link ServerBuilder#requestTimeout(Duration)}.
     */
    static GrpcClientTimeoutHandler disabled() {
        return GrpcClientTimeoutHandlers.DISABLED;
    }

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that limits the timeout requested by the client to the
     * request timeout configured for the {@link ServiceRequestContext}. A client that asks for a longer
     * timeout, or for no timeout at all, gets the server timeout instead.
     *
     * <p>Note that this returns the client timeout as it is if the server has no request timeout configured.
     */
    static GrpcClientTimeoutHandler boundedByServerTimeout() {
        return GrpcClientTimeoutHandlers.BOUNDED_BY_SERVER_TIMEOUT;
    }

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that extends the timeout requested by the client by the
     * specified {@code buffer}, which is useful to compensate for the time spent on the network.
     *
     * @throws IllegalArgumentException if the {@code buffer} is negative
     */
    static GrpcClientTimeoutHandler withBuffer(Duration buffer) {
        requireNonNull(buffer, "buffer");
        if (buffer.isNegative()) {
            throw new IllegalArgumentException("buffer: " + buffer + " (expected: >= 0)");
        }
        if (buffer.isZero()) {
            return enabled();
        }
        return new GrpcClientTimeoutHandlers.WithBuffer(buffer);
    }

    /**
     * Returns the request timeout to set for the specified {@link ServiceRequestContext}.
     *
     * @param ctx the {@link ServiceRequestContext} of the request
     * @param clientTimeout the timeout requested via the {@code grpc-timeout} header. {@link Duration#ZERO}
     *                      means that the client asked for an infinite timeout.
     *
     * @return the timeout to use, or {@link Duration#ZERO} to use an infinite timeout.
     *         {@code null} to leave the request timeout configured for the server untouched.
     */
    @Nullable
    Duration apply(ServiceRequestContext ctx, Duration clientTimeout);
}

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

import java.time.Duration;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;

final class GrpcClientTimeoutHandlers {

    /**
     * The largest timeout {@link TimeoutHeaderUtil#fromHeaderValue(String)} can return, because it saturates
     * on overflow.
     */
    private static final Duration MAX_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

    static final GrpcClientTimeoutHandler ENABLED = new GrpcClientTimeoutHandler() {
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              Duration clientTimeout) {
            return clientTimeout;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.enabled()";
        }
    };

    static final GrpcClientTimeoutHandler DISABLED = new GrpcClientTimeoutHandler() {
        @Nullable
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              Duration clientTimeout) {
            return null;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.disabled()";
        }
    };

    static final GrpcClientTimeoutHandler BOUNDED_BY_SERVER_TIMEOUT = new GrpcClientTimeoutHandler() {
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              Duration clientTimeout) {
            final long serverTimeoutMillis = ctx.config().requestTimeoutMillis();
            if (serverTimeoutMillis == 0) {
                // The server does not have a request timeout, so there is nothing to bound the client
                // timeout with.
                return clientTimeout;
            }
            final Duration serverTimeout = Duration.ofMillis(serverTimeoutMillis);
            if (isInfinite(clientTimeout) || clientTimeout.compareTo(serverTimeout) > 0) {
                return serverTimeout;
            }
            return clientTimeout;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.boundedByServerTimeout()";
        }
    };

    static boolean isInfinite(Duration timeout) {
        return timeout.isZero() || timeout.isNegative();
    }

    static final class WithBuffer implements GrpcClientTimeoutHandler {

        private final Duration buffer;

        WithBuffer(Duration buffer) {
            this.buffer = buffer;
        }

        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              Duration clientTimeout) {
            if (isInfinite(clientTimeout)) {
                return clientTimeout;
            }
            if (clientTimeout.compareTo(MAX_TIMEOUT.minus(buffer)) >= 0) {
                // Adding the buffer would overflow.
                return MAX_TIMEOUT;
            }
            return clientTimeout.plus(buffer);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper("GrpcClientTimeoutHandler.withBuffer")
                              .add("buffer", buffer)
                              .toString();
        }
    }

    private GrpcClientTimeoutHandlers() {}
}

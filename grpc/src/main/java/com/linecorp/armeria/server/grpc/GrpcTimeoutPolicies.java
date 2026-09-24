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
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;

final class GrpcTimeoutPolicies {

    /**
     * The largest timeout that can be scheduled, because a {@link ServiceRequestContext} keeps a timeout in
     * nanoseconds.
     */
    private static final Duration MAX_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

    static final GrpcTimeoutPolicy USE_GRPC_TIMEOUT_HEADER = new GrpcTimeoutPolicy() {
        @Nullable
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            return clientTimeout;
        }

        @Override
        public String toString() {
            return "GrpcTimeoutPolicy.useGrpcTimeoutHeader()";
        }
    };

    static final GrpcTimeoutPolicy USE_SERVICE_TIMEOUT = new GrpcTimeoutPolicy() {
        @Nullable
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            final long serviceTimeoutMillis = ctx.config().requestTimeoutMillis();
            return serviceTimeoutMillis == 0 ? null : Duration.ofMillis(serviceTimeoutMillis);
        }

        @Override
        public GrpcTimeoutPolicy withOffset(Duration offset) {
            // Nothing to shift; the service timeout is what it is.
            return this;
        }

        @Override
        public String toString() {
            return "GrpcTimeoutPolicy.useServiceTimeout()";
        }
    };

    static final class Bounded implements GrpcTimeoutPolicy {

        private final Duration max;

        Bounded(Duration max) {
            this.max = max;
        }

        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            if (clientTimeout == null || clientTimeout.compareTo(max) > 0) {
                return max;
            }
            return clientTimeout;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper("GrpcTimeoutPolicy.useGrpcTimeoutHeader")
                              .add("max", max)
                              .toString();
        }
    }

    static final class WithOffset implements GrpcTimeoutPolicy {

        private final GrpcTimeoutPolicy delegate;
        private final Duration offset;

        WithOffset(GrpcTimeoutPolicy delegate, Duration offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        @Nullable
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            final Duration timeout = delegate.apply(ctx, method, clientTimeout);
            if (timeout == null) {
                // An infinite timeout is left alone.
                return null;
            }
            if (offset.isNegative()) {
                // Shortening a timeout never overflows, and a non-positive result fails the request
                // immediately.
                return timeout.plus(offset);
            }
            if (timeout.compareTo(MAX_TIMEOUT.minus(offset)) >= 0) {
                // Adding the offset would overflow.
                return MAX_TIMEOUT;
            }
            return timeout.plus(offset);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper("GrpcTimeoutPolicy.withOffset")
                              .add("delegate", delegate)
                              .add("offset", offset)
                              .toString();
        }
    }

    private GrpcTimeoutPolicies() {}
}

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

    /**
     * An arbitrary negative {@link Duration}, which tells a caller to leave the request timeout of a
     * {@link ServiceRequestContext} as it is.
     */
    private static final Duration KEEP_TIMEOUT = Duration.ofSeconds(-1);

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
        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            // A ServiceRequestContext already carries the timeout configured for the service, which a
            // decorator may have updated since, so leave it alone instead of reading it back and setting it
            // again.
            return KEEP_TIMEOUT;
        }

        @Override
        public String toString() {
            return "GrpcTimeoutPolicy.useServiceTimeout()";
        }
    };

    static final class Bounded implements GrpcTimeoutPolicy {

        private final Duration max;
        @Nullable
        private final Duration offset;

        Bounded(Duration max, @Nullable Duration offset) {
            this.max = max;
            this.offset = offset;
        }

        @Override
        public Duration apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                              @Nullable Duration clientTimeout) {
            Duration timeout = max;
            if (clientTimeout != null && clientTimeout.compareTo(max) < 0) {
                timeout = clientTimeout;
            }
            if (offset == null) {
                return timeout;
            }
            if (timeout.compareTo(MAX_TIMEOUT.minus(offset)) >= 0) {
                // Adding the offset would overflow.
                return MAX_TIMEOUT;
            }
            final Duration shifted = timeout.plus(offset);
            // A deadline the offset exhausted must fail the request, so it must not come out negative, which
            // would mean leaving the request timeout as it is.
            return shifted.isNegative() ? Duration.ZERO : shifted;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper("GrpcTimeoutPolicy.useGrpcTimeoutHeader")
                              .omitNullValues()
                              .add("max", max)
                              .add("offset", offset)
                              .toString();
        }
    }

    private GrpcTimeoutPolicies() {}
}

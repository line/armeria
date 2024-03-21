/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.client;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.InternalGrpcWebTrailers;

public final class AbstractRuleBuilderUtil {

    /**
     * Merges the filters of {@link AbstractRuleBuilder} that returns {@code true}
     * if all rules in the {@link AbstractRuleBuilder} match
     * a given {@link ClientRequestContext} and {@link Throwable}.
     */
    // TODO(ikhoon): Use BiPredicate.
    public static BiFunction<? super ClientRequestContext, ? super Throwable, Boolean>
    buildFilter(BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter,
                @Nullable BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter,
                @Nullable BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter,
                @Nullable BiPredicate<ClientRequestContext, HttpHeaders> grpcTrailersFilter,
                @Nullable BiPredicate<ClientRequestContext, Throwable> exceptionFilter,
                @Nullable BiPredicate<ClientRequestContext, Duration> totalDurationFilter,
                boolean hasResponseFilter) {

        return new Filter(requestHeadersFilter, exceptionFilter, responseHeadersFilter,
                          responseTrailersFilter, grpcTrailersFilter, totalDurationFilter,
                          hasResponseFilter);
    }

    private AbstractRuleBuilderUtil() {}

    private static class Filter implements BiFunction<ClientRequestContext, Throwable, Boolean> {
        private final BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter;
        private final @Nullable BiPredicate<ClientRequestContext, Throwable> exceptionFilter;
        private final @Nullable BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter;
        private final @Nullable BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter;
        private final @Nullable BiPredicate<ClientRequestContext, HttpHeaders> grpcTrailersFilter;
        private final @Nullable BiPredicate<ClientRequestContext, Duration> totalDurationFilter;
        private final boolean hasResponseFilter;

        Filter(BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter,
               @Nullable BiPredicate<ClientRequestContext, Throwable> exceptionFilter,
               @Nullable BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter,
               @Nullable BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter,
               @Nullable BiPredicate<ClientRequestContext, HttpHeaders> grpcTrailersFilter,
               @Nullable BiPredicate<ClientRequestContext, Duration> totalDurationFilter,
               boolean hasResponseFilter) {
            this.requestHeadersFilter = requestHeadersFilter;
            this.exceptionFilter = exceptionFilter;
            this.responseHeadersFilter = responseHeadersFilter;
            this.responseTrailersFilter = responseTrailersFilter;
            this.grpcTrailersFilter = grpcTrailersFilter;
            this.totalDurationFilter = totalDurationFilter;
            this.hasResponseFilter = hasResponseFilter;
        }

        @Override
        public Boolean apply(ClientRequestContext ctx, Throwable cause) {
            final RequestLog log = ctx.log().partial();
            if (log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
                final RequestHeaders requestHeaders = log.requestHeaders();
                if (!requestHeadersFilter.test(ctx, requestHeaders)) {
                    return false;
                }
            }

            // Safe to return true since no filters are set
            if (exceptionFilter == null && responseHeadersFilter == null &&
                responseTrailersFilter == null && grpcTrailersFilter == null &&
                totalDurationFilter == null && !hasResponseFilter) {
                return true;
            }

            return applySlow(ctx, cause, log);
        }

        private boolean applySlow(ClientRequestContext ctx, @Nullable Throwable cause, RequestLog log) {
            if (cause != null && exceptionFilter != null &&
                exceptionFilter.test(ctx, Exceptions.peel(cause))) {
                return true;
            }

            if (responseHeadersFilter != null && log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final ResponseHeaders responseHeaders = log.responseHeaders();
                if (responseHeadersFilter.test(ctx, responseHeaders)) {
                    return true;
                }
            }

            if (responseTrailersFilter != null && log.isAvailable(RequestLogProperty.RESPONSE_TRAILERS)) {
                final HttpHeaders responseTrailers = log.responseTrailers();
                if (responseTrailersFilter.test(ctx, responseTrailers)) {
                    return true;
                }
            }

            if (grpcTrailersFilter != null && log.isAvailable(RequestLogProperty.RESPONSE_TRAILERS)) {
                // Check HTTP trailers first, because most gRPC responses have non-empty payload + trailers.
                HttpHeaders maybeGrpcTrailers = log.responseTrailers();
                if (!maybeGrpcTrailers.contains("grpc-status")) {
                    // Check HTTP headers secondly.
                    maybeGrpcTrailers = log.responseHeaders();
                    if (!maybeGrpcTrailers.contains("grpc-status")) {
                        // Check gRPC Web trailers lastly, because gRPC Web is the least used protocol
                        // in reality.
                        maybeGrpcTrailers = InternalGrpcWebTrailers.get(ctx);
                    }
                }

                // Suppressing the inspection rule because we don't want to return false too early.
                //noinspection RedundantIfStatement
                if (maybeGrpcTrailers != null && grpcTrailersFilter.test(ctx, maybeGrpcTrailers)) {
                    // Found the matching gRPC trailers.
                    return true;
                }
            }

            if (totalDurationFilter != null && log.isAvailable(RequestLogProperty.RESPONSE_END_TIME)) {
                final long totalDurationNanos = log.totalDurationNanos();
                if (totalDurationFilter.test(ctx, Duration.ofNanos(totalDurationNanos))) {
                    return true;
                }
            }

            return false;
        }
    }
}

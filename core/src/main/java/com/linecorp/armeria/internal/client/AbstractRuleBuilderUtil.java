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

public final class AbstractRuleBuilderUtil {

    /**
     * Merges the filters of {@link AbstractRuleBuilder} that returns {@code true}
     * if all rules in the {@link AbstractRuleBuilder} match
     * a given {@link ClientRequestContext} and {@link Throwable}.
     */
    // TODO(ikhoon): Use BiPredicate.
    public static BiFunction<? super ClientRequestContext, ? super @Nullable Throwable, Boolean>
    buildFilter(BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter,
                @Nullable BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter,
                @Nullable BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter,
                @Nullable BiPredicate<ClientRequestContext, Throwable> exceptionFilter,
                boolean hasResponseFilter) {
        return (ctx, cause) -> {
            final RequestLog log = ctx.log().partial();
            if (log.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
                final RequestHeaders requestHeaders = log.requestHeaders();
                if (!requestHeadersFilter.test(ctx, requestHeaders)) {
                    return false;
                }
            }

            // Safe to return true since no filters are set
            if (exceptionFilter == null && responseHeadersFilter == null &&
                responseTrailersFilter == null && !hasResponseFilter) {
                return true;
            }

            if (cause != null && exceptionFilter != null && exceptionFilter.test(ctx, Exceptions.peel(cause))) {
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

            return false;
        };
    }

    private AbstractRuleBuilderUtil() {}
}

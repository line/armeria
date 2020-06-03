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
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;

public final class AbstractRuleBuilderUtil {

    /**
     * Merges the filters of {@link AbstractRuleBuilder} that returns {@code true}
     * if all rules in the {@link AbstractRuleBuilder} match
     * a given {@link ClientRequestContext} and {@link Throwable}.
     */
    public static BiFunction<? super ClientRequestContext, ? super Throwable, Boolean>
    buildFilter(Predicate<RequestHeaders> requestHeadersFilter,
                @Nullable Predicate<ResponseHeaders> responseHeadersFilter,
                @Nullable Predicate<Throwable> exceptionFilter,
                boolean hasResponseFilter) {
        return (ctx, cause) -> {
            if (ctx.log().isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
                final RequestHeaders requestHeaders = ctx.log().partial().requestHeaders();
                if (!requestHeadersFilter.test(requestHeaders)) {
                    return false;
                }
            }

            // Safe to return true since no filters are set
            if (exceptionFilter == null && responseHeadersFilter == null && !hasResponseFilter) {
                return true;
            }

            if (cause != null && exceptionFilter != null && exceptionFilter.test(Exceptions.peel(cause))) {
                return true;
            }

            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final ResponseHeaders responseHeaders = ctx.log().partial().responseHeaders();
                if (responseHeadersFilter != null && responseHeadersFilter.test(responseHeaders)) {
                    return true;
                }
            }

            return false;
        };
    }

    private AbstractRuleBuilderUtil() {}
}

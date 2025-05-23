/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.hedging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

@FunctionalInterface
public interface HedgingRuleWithContent<T extends Response> {
    static <T extends Response> HedgingRuleWithContent<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> hedgingFunction,
            long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onResponse(hedgingFunction).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onStatusClass(HttpStatusClass statusClass,
                                                                      long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onStatusClass(statusClass).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onStatusClass(Iterable<HttpStatusClass> statusClasses,
                                                                      long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onStatusClass(statusClasses).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onServerErrorStatus(long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onServerErrorStatus().thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onStatus(Iterable<HttpStatus> statuses,
                                                                 long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onStatus(statuses).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter, long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onStatus(statusFilter).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onException(Class<? extends Throwable> exception,
                                                                    long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onException(exception).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter, long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onException(exceptionFilter).thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onException(long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onException().thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContent<T> onUnprocessed(long hedgingDelayMillis) {
        return HedgingRuleWithContent.<T>builder().onUnprocessed().thenHedge(hedgingDelayMillis);
    }

    static <T extends Response> HedgingRuleWithContentBuilder<T> builder() {
        return builder(HttpMethod.knownMethods());
    }

    static <T extends Response> HedgingRuleWithContentBuilder<T> builder(HttpMethod... methods) {
        return builder(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    static <T extends Response> HedgingRuleWithContentBuilder<T> builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((ctx, headers) -> httpMethods.contains(headers.method()));
    }

    static <T extends Response> HedgingRuleWithContentBuilder<T> builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        requireNonNull(requestHeadersFilter, "requestHeadersFilter");
        return new HedgingRuleWithContentBuilder<>(requestHeadersFilter);
    }

    @SafeVarargs
    static <T extends Response> HedgingRuleWithContent<T> of(HedgingRuleWithContent<T>... hedgingRules) {
        requireNonNull(hedgingRules, "hedgingRules");
        checkArgument(hedgingRules.length > 0, "hedgingRules can't be empty.");
        if (hedgingRules.length == 1) {
            return hedgingRules[0];
        }
        return of(ImmutableList.copyOf(hedgingRules));
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static <T extends Response> HedgingRuleWithContent<T> of(
            Iterable<? extends HedgingRuleWithContent<T>> hedgingRules) {
        requireNonNull(hedgingRules, "hedgingRules");
        checkArgument(!Iterables.isEmpty(hedgingRules), "hedgingRules should not be empty.");
        if (Iterables.size(hedgingRules) == 1) {
            return Iterables.get(hedgingRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<HedgingRuleWithContent<T>> cast = (Iterable<HedgingRuleWithContent<T>>) hedgingRules;
        return Streams.stream(cast).reduce(HedgingRuleWithContent::orElse).get();
    }


    default HedgingRuleWithContent<T> orElse(HedgingRule other) {
        requireNonNull(other, "other");
        return HedgingRuleUtil.orElse(this, other);
    }


    default HedgingRuleWithContent<T> orElse(HedgingRuleWithContent<T> other) {
        requireNonNull(other, "other");
        return HedgingRuleUtil.orElse(this, other);
    }

    CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable T response,
                                               @Nullable Throwable cause);
    
    default boolean requiresResponseTrailers() {
        return false;
    }
}

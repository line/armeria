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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

@FunctionalInterface
public interface HedgingRule {
    static HedgingRule failsafe(long hedgingDelayMillis) {
        return of(builder(HttpMethod.idempotentMethods()).onServerErrorStatus()
                                                         .onException()
                                                         .thenHedge(hedgingDelayMillis),
                  onUnprocessed(hedgingDelayMillis));
    }

    static HedgingRule onStatusClass(HttpStatusClass statusClass, long hedgingDelayMillis) {
        return builder().onStatusClass(statusClass).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onStatusClass(Iterable<HttpStatusClass> statusClasses, long hedgingDelayMillis) {
        return builder().onStatusClass(statusClasses).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onServerErrorStatus(long hedgingDelayMillis) {
        return builder().onServerErrorStatus().thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onStatus(Iterable<HttpStatus> statuses, long hedgingDelayMillis) {
        return builder().onStatus(statuses).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onStatus(BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter,
                                long hedgingDelayMillis) {
        return builder().onStatus(statusFilter).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onException(Class<? extends Throwable> exception, long hedgingDelayMillis) {
        return builder().onException(exception).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onException(BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter,
                                   long hedgingDelayMillis) {
        return builder().onException(exceptionFilter).thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onException(long hedgingDelayMillis) {
        return builder().onException().thenHedge(hedgingDelayMillis);
    }

    @UnstableApi
    static HedgingRule onTimeoutException(long hedgingDelayMillis) {
        return builder().onTimeoutException().thenHedge(hedgingDelayMillis);
    }

    static HedgingRule onUnprocessed(long hedgingDelayMillis) {
        return builder().onUnprocessed().thenHedge(hedgingDelayMillis);
    }

    static HedgingRuleBuilder builder() {
        return builder(HttpMethod.knownMethods());
    }

    static HedgingRuleBuilder builder(HttpMethod... methods) {
        requireNonNull(methods, "methods");
        return builder(ImmutableSet.copyOf(methods));
    }

    static HedgingRuleBuilder builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "method can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((ctx, headers) -> httpMethods.contains(headers.method()));
    }

    static HedgingRuleBuilder builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        return new HedgingRuleBuilder(requireNonNull(requestHeadersFilter, "requestHeadersFilter"));
    }

    static HedgingRule of(HedgingRule... hedgingRules) {
        requireNonNull(hedgingRules, "hedgingRules");
        checkArgument(hedgingRules.length > 0, "hedgingRules can't be empty.");
        if (hedgingRules.length == 1) {
            return hedgingRules[0];
        }
        return of(ImmutableList.copyOf(hedgingRules));
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static HedgingRule of(Iterable<? extends HedgingRule> hedgingRules) {
        requireNonNull(hedgingRules, "hedgingRules");
        checkArgument(!Iterables.isEmpty(hedgingRules), "hedgingRules can't be empty.");
        if (Iterables.size(hedgingRules) == 1) {
            return Iterables.get(hedgingRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<HedgingRule> cast = (Iterable<HedgingRule>) hedgingRules;
        return Streams.stream(cast).reduce(HedgingRule::orElse).get();
    }

    default HedgingRule orElse(HedgingRule other) {
        return HedgingRuleUtil.orElse(this, requireNonNull(other, "other"));
    }

    CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable Throwable cause);

    default boolean requiresResponseTrailers() {
        return false;
    }
}

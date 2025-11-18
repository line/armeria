/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.outlier;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating an {@link OutlierRule}.
 */
@UnstableApi
public final class OutlierDetectingRuleBuilder {

    static final OutlierRule DEFAULT_RULE =
            OutlierRule.builder()
                       .onServerError()
                       .onException()
                       .build();

    @Nullable
    private OutlierRule rules;

    OutlierDetectingRuleBuilder() {}

    /**
     * Reports a failure if the response status is a server error (5xx).
     */
    public OutlierDetectingRuleBuilder onServerError() {
        return onResponseHeaders((ctx, headers) -> {
            if (headers.status().isServerError()) {
                return OutlierDetectionDecision.FAILURE;
            }
            return OutlierDetectionDecision.NEXT;
        });
    }

    /**
     * Reports a failure if the response status is the specified {@link HttpStatus}.
     */
    public OutlierDetectingRuleBuilder onStatus(HttpStatus status) {
        return onStatus(status, OutlierDetectionDecision.FAILURE);
    }

    /**
     * Reports the specified {@link OutlierDetectionDecision} if the response status is the specified
     * {@link HttpStatus}.
     */
    public OutlierDetectingRuleBuilder onStatus(HttpStatus status, OutlierDetectionDecision decision) {
        requireNonNull(status, "status");
        requireNonNull(decision, "decision");
        return onStatus(status::equals, decision);
    }

    /**
     * Reports the specified {@link OutlierDetectionDecision} if the response status matches the specified
     * {@link Predicate}.
     */
    public OutlierDetectingRuleBuilder onStatus(Predicate<? super HttpStatus> predicate,
                                                OutlierDetectionDecision decision) {
        requireNonNull(predicate, "predicate");
        requireNonNull(decision, "decision");

        return onResponseHeaders((ctx, headers) -> {
            if (predicate.test(headers.status())) {
                return decision;
            }
            return OutlierDetectionDecision.NEXT;
        });
    }

    /**
     * Adds the specified function to determine whether the request is an outlier.
     */
    public OutlierDetectingRuleBuilder onResponseHeaders(
            BiFunction<? super RequestContext, ? super ResponseHeaders, OutlierDetectionDecision> function) {
        requireNonNull(function, "function");
        final OutlierRule rule = (ctx, headers, cause) -> {
            if (headers == null) {
                return OutlierDetectionDecision.NEXT;
            }
            return function.apply(ctx, headers);
        };
        addRule(rule);
        return this;
    }

    /**
     * Adds the specified function to determine whether the request is an outlier.
     */
    public OutlierDetectingRuleBuilder onException(
            BiFunction<? super RequestContext, ? super Throwable, OutlierDetectionDecision> function) {
        requireNonNull(function, "function");
        final OutlierRule rule = (ctx, headers, cause) -> {
            if (cause == null) {
                return OutlierDetectionDecision.NEXT;
            }
            return function.apply(ctx, cause);
        };
        addRule(rule);
        return this;
    }

    /**
     * Reports a failure if an exception is raised.
     */
    public OutlierDetectingRuleBuilder onException() {
        return onException((ctx, cause) -> OutlierDetectionDecision.FAILURE);
    }

    /**
     * Reports a failure if the exception type is raised.
     */
    public OutlierDetectingRuleBuilder onException(Class<? extends Throwable> exceptionType) {
        return onException(exceptionType, OutlierDetectionDecision.FAILURE);
    }

    /**
     * Reports the specified {@link OutlierDetectionDecision} if the exception type is raised.
     */
    public OutlierDetectingRuleBuilder onException(Class<? extends Throwable> exceptionType,
                                                   OutlierDetectionDecision decision) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(decision, "decision");
        onException((ctx, cause) -> {
            if (exceptionType.isInstance(cause)) {
                return decision;
            }
            return OutlierDetectionDecision.NEXT;
        });
        return this;
    }

    private void addRule(OutlierRule newRule) {
        if (rules == null) {
            rules = newRule;
        } else {
            rules = rules.orElse(newRule);
        }
    }

    /**
     * Builds a new {@link OutlierRule} based on the added rules.
     */
    public OutlierRule build() {
        checkState(rules != null, "No rule has been added.");
        return rules;
    }
}

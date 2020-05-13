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

package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * A builder which creates a {@link RetryRuleWithContent}.
 */
public final class RetryRuleWithContentBuilder<T extends Response> extends AbstractRetryRuleBuilder {

    @Nullable
    private Function<? super T, ? extends CompletionStage<Boolean>> retryFunction;

    RetryRuleWithContentBuilder(Predicate<? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Adds the specified {@code retryFunction} for a {@link RetryRuleWithContent} which will retry
     * if the specified {@code retryFunction} completes with {@code true}.
     */
    public RetryRuleWithContentBuilder<T> onResponse(
            Function<? super T, ? extends CompletionStage<Boolean>> retryFunction) {
        requireNonNull(retryFunction, "retryFunction");

        if (this.retryFunction == null) {
            this.retryFunction = retryFunction;
        } else {
            final Function<? super T, ? extends CompletionStage<Boolean>> first = this.retryFunction;
            this.retryFunction = content -> {
                final CompletionStage<Boolean> result = first.apply(content);
                return result.thenCompose(matched -> {
                    if (matched) {
                        return result;
                    } else {
                        return retryFunction.apply(content);
                    }
                });
            };
        }
        return this;
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and
     * returns a newly created {@link RetryRuleWithContent}.
     */
    public RetryRuleWithContent<T> thenBackoff() {
        return thenBackoff(Backoff.ofDefault());
    }

    /**
     * Sets the specified {@link Backoff} and returns a newly created {@link RetryRuleWithContent}.
     */
    public RetryRuleWithContent<T> thenBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return build(RetryDecision.retry(backoff));
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that never retries.
     */
    public RetryRuleWithContent<T> thenNoRetry() {
        return build(RetryDecision.noRetry());
    }

    RetryRuleWithContent<T> build(RetryDecision decision) {
        if (decision != RetryDecision.noRetry() && exceptionFilter() == null &&
            responseHeadersFilter() == null && retryFunction == null) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }

        final RetryRule first = RetryRuleBuilder.build(this, decision);
        if (retryFunction == null) {
            return RetryRuleUtil.fromRetryRule(first);
        }

        final RetryRuleWithContent<T> second = (ctx, content) ->
                retryFunction.apply(content)
                             .handle((matched, cause) -> {
                                 if (cause != null) {
                                     return RetryDecision.next();
                                 }
                                 return matched ? decision : RetryDecision.next();
                             });
        return RetryRuleUtil.orElse(first, second);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("exceptionFilter", exceptionFilter())
                          .add("requestHeadersFilter", requestHeadersFilter())
                          .add("responseHeadersFilter", responseHeadersFilter())
                          .add("retryFunction", retryFunction)
                          .toString();
    }

    // Override the return type of the chaining methods in the superclass.

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onResponseHeaders(
            Predicate<? super ResponseHeaders> responseHeadersFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onResponseHeaders(responseHeadersFilter);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatusClass(HttpStatusClass... statusClasses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onServerErrorStatus() {
        return (RetryRuleWithContentBuilder<T>) super.onServerErrorStatus();
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(HttpStatus... statuses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(Iterable<HttpStatus> statuses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(Predicate<? super HttpStatus> statusFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statusFilter);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException(Class<? extends Throwable> exception) {
        return (RetryRuleWithContentBuilder<T>) super.onException(exception);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException(Predicate<? super Throwable> exceptionFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onException(exceptionFilter);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException() {
        return (RetryRuleWithContentBuilder<T>) super.onException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onUnprocessed() {
        return (RetryRuleWithContentBuilder<T>) super.onUnprocessed();
    }
}

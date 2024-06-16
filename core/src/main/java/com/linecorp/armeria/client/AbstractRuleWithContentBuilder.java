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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for {@link RetryRuleWithContent} and {@link CircuitBreakerRuleWithContent}.
 * @param <T> the response type
 */
@UnstableApi
public abstract class AbstractRuleWithContentBuilder
        <SELF extends AbstractRuleWithContentBuilder<SELF, T>, T extends Response>
        extends AbstractRuleBuilder<SELF> {

    @Nullable
    private BiFunction<? super ClientRequestContext, ? super T, ? extends CompletionStage<Boolean>>
            responseFilter;

    /**
     * Creates a new instance with the specified {@code requestHeadersFilter}.
     */
    protected AbstractRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Adds the specified {@code responseFilter}.
     */
    @SuppressWarnings("unchecked")
    public SELF onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> responseFilter) {
        requireNonNull(responseFilter, "responseFilter");

        if (this.responseFilter == null) {
            this.responseFilter = responseFilter;
        } else {
            final BiFunction<? super ClientRequestContext, ? super T, ? extends CompletionStage<Boolean>>
                    first = this.responseFilter;
            this.responseFilter = (ctx, content) -> {
                if (content instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) content).toDuplicator();
                    final CompletionStage<Boolean> result = first.apply(ctx, (T) duplicator.duplicate());

                    return result.thenCompose(matched -> {
                        if (matched) {
                            return result;
                        } else {
                            return responseFilter.apply(ctx, (T) duplicator.duplicate());
                        }
                    }).whenComplete((unused1, unused2) -> duplicator.close());
                } else {
                    final CompletionStage<Boolean> result = first.apply(ctx, content);
                    return result.thenCompose(matched -> {
                        if (matched) {
                            return result;
                        } else {
                            return responseFilter.apply(ctx, content);
                        }
                    });
                }
            };
        }
        return self();
    }

    /**
     * Returns the {@code responseFilter}.
     */
    @Nullable
    protected final BiFunction<? super ClientRequestContext, ? super T, ? extends CompletionStage<Boolean>>
    responseFilter() {
        return responseFilter;
    }
}

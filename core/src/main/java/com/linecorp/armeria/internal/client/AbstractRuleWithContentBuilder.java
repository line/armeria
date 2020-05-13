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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;

/**
 * A skeletal builder implementation for {@link RetryRuleWithContent} and {@link CircuitBreakerRuleWithContent}.
 * @param <T> the response type
 */
public abstract class AbstractRuleWithContentBuilder<T extends Response> extends AbstractRuleBuilder {

    @Nullable
    private Function<? super T, ? extends CompletionStage<Boolean>> responseFilter;

    protected AbstractRuleWithContentBuilder(
            Predicate<? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Adds the specified {@code responseFilter} for a {@link AbstractRuleWithContentBuilder}.
     */
    @SuppressWarnings("unchecked")
    public AbstractRuleWithContentBuilder<T> onResponse(
            Function<? super T, ? extends CompletionStage<Boolean>> responseFilter) {
        requireNonNull(responseFilter, "responseFilter");

        if (this.responseFilter == null) {
            this.responseFilter = responseFilter;
        } else {
            final Function<? super T, ? extends CompletionStage<Boolean>> first = this.responseFilter;
            this.responseFilter = content -> {
                if (content instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) content).toDuplicator();
                    final CompletionStage<Boolean> result = first.apply((T) duplicator.duplicate());

                    return result.thenCompose(matched -> {
                        if (matched) {
                            return result;
                        } else {
                            return responseFilter.apply((T) duplicator.duplicate());
                        }
                    }).whenComplete((unused1, unused2) -> duplicator.close());
                } else {
                    final CompletionStage<Boolean> result = first.apply(content);
                    return result.thenCompose(matched -> {
                        if (matched) {
                            return result;
                        } else {
                            return responseFilter.apply(content);
                        }
                    });
                }
            };
        }
        return this;
    }

    @Nullable
    protected final Function<? super T, ? extends CompletionStage<Boolean>> responseFilter() {
        return responseFilter;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("exceptionFilter", exceptionFilter())
                          .add("requestHeadersFilter", requestHeadersFilter())
                          .add("responseHeadersFilter", responseHeadersFilter())
                          .add("responseFilter", responseFilter)
                          .toString();
    }
}

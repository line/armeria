/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.internal.HttpHeaderSubscriber;

/**
 * Provides a {@link RetryStrategy} that decides to retry the request based on the {@link HttpStatus} of
 * its response.
 */
final class HttpStatusBasedRetryStrategy implements RetryStrategy<HttpRequest, HttpResponse> {

    private final List<HttpStatus> retryStatuses;

    /**
     * Creates a new instance.
     */
    HttpStatusBasedRetryStrategy(Iterable<HttpStatus> retryStatuses) {
        this.retryStatuses = validateStatus(ImmutableList.copyOf(retryStatuses));
    }

    private List<HttpStatus> validateStatus(List<HttpStatus> retryStatuses) {
        requireNonNull(retryStatuses, "retryStatuses");
        checkArgument(!retryStatuses.isEmpty(), "Need at least a status to retry");

        for (HttpStatus retryStatus : retryStatuses) {
            checkArgument(retryStatus.codeClass() != HttpStatusClass.INFORMATIONAL,
                          "retryStatuses contains an informational status: %s", retryStatus);
        }
        return retryStatuses;
    }

    @Override
    public CompletableFuture<Boolean> shouldRetry(HttpRequest request, HttpResponse response) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpHeaderSubscriber subscriber = new HttpHeaderSubscriber(future);
        response.closeFuture().whenComplete(subscriber);
        response.subscribe(subscriber);

        return future.handle((message, unused) ->
                                     message != null && retryStatuses.stream().anyMatch(retryStatus -> {
                                         final HttpStatus resStatus = message.headers().status();
                                         return resStatus != null && resStatus.code() == retryStatus.code();
                                     }));
    }
}

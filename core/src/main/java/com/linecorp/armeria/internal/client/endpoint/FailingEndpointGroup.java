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

package com.linecorp.armeria.internal.client.endpoint;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

public final class FailingEndpointGroup implements EndpointGroup {

    private static final FailingEndpointGroup INSTANCE =
            new FailingEndpointGroup(new IllegalArgumentException(
                    "An endpointGroup has not been specified. Specify an endpointGroup by " +
                    "1) building a client with a URI or EndpointGroup e.g. 'WebClient.of(uri)', " +
                    "2) sending a request with the authority 'client.execute(requestWithAuthority)', or " +
                    "3) setting the endpointGroup directly inside a Preprocessor via 'ctx.endpointGroup()'."));

    public static FailingEndpointGroup of() {
        return INSTANCE;
    }

    private final RuntimeException exception;
    private final CompletableFuture<Endpoint> failedFuture;

    private FailingEndpointGroup(Throwable throwable) {
        exception = UnprocessedRequestException.of(throwable);
        failedFuture = UnmodifiableFuture.exceptionallyCompletedFuture(exception);
    }

    @Override
    public List<Endpoint> endpoints() {
        return ImmutableList.of();
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return EndpointSelectionStrategy.roundRobin();
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        throw exception;
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return failedFuture;
    }

    @Override
    public long selectionTimeoutMillis() {
        return 0;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("exception", exception)
                          .toString();
    }
}

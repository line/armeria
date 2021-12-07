/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;

final class OrElseEndpointGroup extends AbstractEndpointGroup implements ListenableAsyncCloseable {

    private final EndpointGroup first;
    private final EndpointGroup second;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;
    private final EndpointSelector selector;

    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    OrElseEndpointGroup(EndpointGroup first, EndpointGroup second) {
        this.first = requireNonNull(first, "first");
        this.second = requireNonNull(second, "second");
        first.addListener(unused -> notifyListeners(endpoints()));
        second.addListener(unused -> notifyListeners(endpoints()));

        initialEndpointsFuture = CompletableFuture
                .anyOf(first.whenReady(), second.whenReady())
                .thenApply(unused -> endpoints());

        selector = first.selectionStrategy().newSelector(this);
    }

    @Override
    public List<Endpoint> endpoints() {
        final List<Endpoint> endpoints = first.endpoints();
        if (!endpoints.isEmpty()) {
            return endpoints;
        }
        return second.endpoints();
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return first.selectionStrategy();
    }

    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return selector.selectNow(ctx);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return selector.select(ctx, executor, timeoutMillis);
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return initialEndpointsFuture;
    }

    @Override
    public boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        CompletableFuture.allOf(first.closeAsync(), second.closeAsync()).handle((unused, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(null);
            }
            return null;
        });
    }

    @Override
    public void close() {
        closeable.close();
    }
}

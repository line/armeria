/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Counts down a user provided counter each time an endpoint is selected asynchronously.
 * Once the countdown is complete, the user defined behavior is executed.
 */
public final class CountDownEmptyEndpointStrategy implements EndpointSelectionStrategy {

    final AtomicInteger selectCounter;
    final Function<ClientRequestContext, CompletableFuture<Endpoint>> selectBehavior;

    public CountDownEmptyEndpointStrategy(
            int selectLimit,
            Function<ClientRequestContext, CompletableFuture<Endpoint>> selectBehavior) {
        selectCounter = new AtomicInteger(selectLimit);
        this.selectBehavior = selectBehavior;
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        final EndpointGroup delegate = new DynamicEndpointGroup();
        return new EndpointSelector() {

            @Override
            @Nullable
            public Endpoint selectNow(ClientRequestContext ctx) {
                return delegate.selectNow(ctx);
            }

            @Override
            public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                      ScheduledExecutorService executor,
                                                      long timeoutMillis) {
                if (selectCounter.decrementAndGet() <= 0) {
                    return selectBehavior.apply(ctx);
                }
                return delegate.select(ctx, executor, timeoutMillis);
            }
        };
    }
}

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
package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

class AbstractEndpointSelectorTest {

    @Test
    void immediateSelection() {
        final Endpoint endpoint = Endpoint.of("foo");
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final AbstractEndpointSelector endpointSelector = newSelector(endpoint);
        assertThat(endpointSelector.select(ctx, ctx.eventLoop(), Long.MAX_VALUE))
                .isCompletedWithValue(endpoint);
        assertThat(endpointSelector.pendingFutures()).isEmpty();
    }

    @Test
    void delayedSelection() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final AbstractEndpointSelector endpointSelector = newSelector(group);
        final CompletableFuture<Endpoint> future = endpointSelector.select(ctx, ctx.eventLoop(),
                                                                           Long.MAX_VALUE);
        assertThat(future).isNotDone();

        final Endpoint endpoint = Endpoint.of("foo");
        group.addEndpoint(endpoint);
        assertThat(future.join()).isSameAs(endpoint);
        assertThat(endpointSelector.pendingFutures()).isEmpty();
    }

    @Test
    void bulkUpdate() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final AbstractEndpointSelector endpointSelector = newSelector(group);
        final List<CompletableFuture<Endpoint>> futures = IntStream.range(0, 10).mapToObj(i -> {
            return endpointSelector.select(ctx, ctx.eventLoop(), Long.MAX_VALUE);
        }).collect(toImmutableList());

        final List<Endpoint> endpoints = ImmutableList.of(Endpoint.of("foo"));
        group.setEndpoints(endpoints);
        for (CompletableFuture<Endpoint> future : futures) {
            assertThat(future.join()).isEqualTo(endpoints.get(0));
        }
        assertThat(endpointSelector.pendingFutures()).isEmpty();
    }

    @Test
    void timeout() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final AbstractEndpointSelector endpointSelector = newSelector(group);
        final CompletableFuture<Endpoint> future =
                endpointSelector.select(ctx, ctx.eventLoop(), 1000)
                                .handle((res, cause) -> {
                                    // Must be invoked from the event loop thread.
                                    assertThat(ctx.eventLoop().inEventLoop()).isTrue();

                                    if (cause != null) {
                                        Exceptions.throwUnsafely(cause);
                                    }

                                    return res;
                                });
        assertThat(future).isNotDone();

        final Stopwatch stopwatch = Stopwatch.createStarted();
        assertThat(future.join()).isNull();
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isGreaterThan(900);
        assertThat(endpointSelector.pendingFutures()).isEmpty();
    }

    @Test
    void testSelectionTimeoutException() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        assertThatThrownBy(() -> WebClient.of(SessionProtocol.HTTP, endpointGroup).get("/").aggregate().join())
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(EndpointSelectionTimeoutException.class);
    }

    @Test
    void testRampingUpInitialSelection() {
        final DynamicEndpointGroup endpointGroup =
                new DynamicEndpointGroup(EndpointSelectionStrategy.rampingUp());
        final Endpoint endpoint = Endpoint.of("foo.com");
        endpointGroup.setEndpoints(ImmutableList.of(endpoint));
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Endpoint selected = endpointGroup.select(ctx, ctx.eventLoop()).join();
        assertThat(selected).isEqualTo(endpoint);
    }

    private static AbstractEndpointSelector newSelector(EndpointGroup endpointGroup) {
        final AbstractEndpointSelector selector = new AbstractEndpointSelector(endpointGroup) {

            @Nullable
            @Override
            public Endpoint selectNow(ClientRequestContext ctx) {
                final List<Endpoint> endpoints = endpointGroup.endpoints();
                return endpoints.isEmpty() ? null : endpoints.get(0);
            }
        };
        selector.initialize();
        return selector;
    }
}

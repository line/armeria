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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

class AbstractEndpointSelectorTest {

    @Test
    void immediateSelection() {
        final Endpoint endpoint = Endpoint.of("foo");
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(newSelector(endpoint).select(ctx, ctx.eventLoop(), Long.MAX_VALUE))
                .isCompletedWithValue(endpoint);
    }

    @Test
    void delayedSelection() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CompletableFuture<Endpoint> future = newSelector(group).select(ctx, ctx.eventLoop(),
                                                                             Long.MAX_VALUE);
        assertThat(future).isNotDone();

        final Endpoint endpoint = Endpoint.of("foo");
        group.addEndpoint(endpoint);
        assertThat(future.join()).isSameAs(endpoint);
    }

    @Test
    void timeout() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CompletableFuture<Endpoint> future =
                newSelector(group).select(ctx, ctx.eventLoop(), 1000)
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
    }

    private static EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new AbstractEndpointSelector(endpointGroup) {
            @Nullable
            @Override
            public Endpoint selectNow(ClientRequestContext ctx) {
                final List<Endpoint> endpoints = endpointGroup.endpoints();
                return endpoints.isEmpty() ? null : endpoints.get(0);
            }
        };
    }
}

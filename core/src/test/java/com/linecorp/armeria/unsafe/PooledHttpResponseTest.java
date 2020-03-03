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

package com.linecorp.armeria.unsafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoop;

class PooledHttpResponseTest {

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", ((ctx, req) -> HttpResponse.of("payload")));
        }
    };

    @RegisterExtension
    public static final EventLoopExtension eventLoop = new EventLoopExtension(true);

    private WebClient unpooledClient;
    private PooledWebClient client;

    @BeforeEach
    void setUp() {
        unpooledClient = WebClient.of(server.httpUri());
        client = PooledWebClient.of(unpooledClient);
    }

    @Test
    void aggregateWithPooledObjects() {
        final EventLoop executor = eventLoop.get();
        client.get("/hello").aggregateWithPooledObjects().thenAccept(
                (response) -> {
                    try (PooledAggregatedHttpResponse unused = response) {
                        assertThat(response.contentUtf8()).isEqualTo("payload");
                        // If the aggregator did not subscribe for pooled buffers, this will be a heap buffer.
                        // This effectively relies on an implementation detail for the test behavior but should
                        // be fine in practice.
                        assertThat(response.content().content().isDirect()).isTrue();
                        assertThat(executor.inEventLoop()).isFalse();
                    }
                }).join();
    }

    @Test
    void aggregateWithPooledObjects_eventExecutor() {
        final EventLoop executor = eventLoop.get();
        client.get("/hello").aggregateWithPooledObjects(executor).thenAccept(
                (response) -> {
                    try (PooledAggregatedHttpResponse unused = response) {
                        assertThat(response.contentUtf8()).isEqualTo("payload");
                        // If the aggregator did not subscribe for pooled buffers, this will be a heap buffer.
                        // This effectively relies on an implementation detail for the test behavior but should
                        // be fine in practice.
                        assertThat(response.content().content().isDirect()).isTrue();
                        assertThat(executor.inEventLoop()).isTrue();
                    }
                }).join();
    }

    @Test
    void aggregateWithPooledObjects_eventExecutorAndAlloc() {
        final ByteBufAllocator alloc = new PooledByteBufAllocator(true);

        final EventLoop executor = eventLoop.get();
        client.get("/hello").aggregateWithPooledObjects(executor, alloc).thenAccept(
                (response) -> {
                    try (PooledAggregatedHttpResponse unused = response) {
                        assertThat(response.contentUtf8()).isEqualTo("payload");
                        // If the aggregator did not subscribe for pooled buffers, this will be a heap buffer.
                        // This effectively relies on an implementation detail for the test behavior but should
                        // be fine in practice.
                        assertThat(response.content().content().isDirect()).isTrue();
                        assertThat(executor.inEventLoop()).isTrue();
                        assertThat(response.content().content().alloc()).isSameAs(alloc);
                    }
                }).join();
    }

    @Test
    void unpooledAggregate() {
        assertThatThrownBy(() -> client.get("/hello").aggregate())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> client.get("/hello").aggregate(eventLoop.get()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

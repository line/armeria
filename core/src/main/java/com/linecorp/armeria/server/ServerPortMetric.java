/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.ServerMetrics.ALL_CONNECTIONS_METER_NAME;
import static com.linecorp.armeria.server.ServerMetrics.ALL_REQUESTS_METER_NAME;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.DomainSocketAddress;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.netty.util.AttributeKey;

final class ServerPortMetric {

    static final AttributeKey<ServerPortMetric> SERVER_PORT_METRIC =
            AttributeKey.valueOf(ServerPortMetric.class, "SERVER_PORT_METRIC");

    private final LongAdder pendingHttp1Requests = new LongAdder();
    private final LongAdder pendingHttp2Requests = new LongAdder();
    private final LongAdder activeHttp1WebSocketRequests = new LongAdder();
    private final LongAdder activeHttp1Requests = new LongAdder();
    private final LongAdder activeHttp2Requests = new LongAdder();
    private final LongAdder activeConnections = new LongAdder();

    void increasePendingHttp1Requests() {
        pendingHttp1Requests.increment();
    }

    void decreasePendingHttp1Requests() {
        pendingHttp1Requests.decrement();
    }

    void increasePendingHttp2Requests() {
        pendingHttp2Requests.increment();
    }

    void decreasePendingHttp2Requests() {
        pendingHttp2Requests.decrement();
    }

    void increaseActiveHttp1WebSocketRequests() {
        activeHttp1WebSocketRequests.increment();
    }

    void decreaseActiveHttp1WebSocketRequests() {
        activeHttp1WebSocketRequests.decrement();
    }

    void increaseActiveHttp1Requests() {
        activeHttp1Requests.increment();
    }

    void decreaseActiveHttp1Requests() {
        activeHttp1Requests.decrement();
    }

    void increaseActiveHttp2Requests() {
        activeHttp2Requests.increment();
    }

    void decreaseActiveHttp2Requests() {
        activeHttp2Requests.decrement();
    }

    void increaseActiveConnections() {
        activeConnections.increment();
    }

    void decreaseActiveConnections() {
        activeConnections.decrement();
    }

    long pendingHttp1Requests() {
        return pendingHttp1Requests.sum();
    }

    long pendingHttp2Requests() {
        return pendingHttp2Requests.sum();
    }

    long activeHttp1WebSocketRequests() {
        return activeHttp1WebSocketRequests.sum();
    }

    long activeHttp1Requests() {
        return activeHttp1Requests.sum();
    }

    long activeHttp2Requests() {
        return activeHttp2Requests.sum();
    }

    long activeConnections() {
        return activeConnections.sum();
    }

    void bindTo(MeterRegistry meterRegistry, ServerPort actualPort) {
        final InetSocketAddress address = actualPort.localAddress();
        final Tag portTag;
        if (address instanceof DomainSocketAddress) {
            final String path = ((DomainSocketAddress) address).path();
            // The path is used as the 'port' tag.
            portTag = Tag.of("port", path);
        } else {
            portTag = Tag.of("port", String.valueOf(address.getPort()));
        }

        meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                            ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                             Tag.of("state", "pending")),
                            this, ServerPortMetric::pendingHttp1Requests);
        meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                            ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                             Tag.of("state", "pending")),
                            this, ServerPortMetric::pendingHttp2Requests);
        meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                            ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                             Tag.of("state", "active")),
                            this, ServerPortMetric::activeHttp1Requests);
        meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                            ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                             Tag.of("state", "active")),
                            this, ServerPortMetric::activeHttp2Requests);
        meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                            ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                             Tag.of("state", "active_websocket")),
                            this, ServerPortMetric::activeHttp1WebSocketRequests);
        meterRegistry.gauge(ALL_CONNECTIONS_METER_NAME, ImmutableList.of(portTag),
                            this, ServerPortMetric::activeConnections);
    }

    // Use reference equality for comparison.

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("pendingHttp1Requests", pendingHttp1Requests)
                          .add("pendingHttp2Requests", pendingHttp2Requests)
                          .add("activeHttp1WebSocketRequests", activeHttp1WebSocketRequests)
                          .add("activeHttp1Requests", activeHttp1Requests)
                          .add("activeHttp2Requests", activeHttp2Requests)
                          .add("activeConnections", activeConnections)
                          .toString();
    }
}

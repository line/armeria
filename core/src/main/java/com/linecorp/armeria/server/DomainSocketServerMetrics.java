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

import static com.linecorp.armeria.server.DefaultServerMetrics.ALL_REQUESTS_METER_NAME;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.DomainSocketAddress;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class DomainSocketServerMetrics implements ServerMetrics {

    private final Map<String, LongAdder> pendingHttp1Requests;
    private final Map<String, LongAdder> pendingHttp2Requests;
    private final Map<String, LongAdder> activeHttp1WebSocketRequests;
    private final Map<String, LongAdder> activeHttp1Requests;
    private final Map<String, LongAdder> activeHttp2Requests;
    private final Map<String, LongAdder> activeConnections;

    DomainSocketServerMetrics(Iterable<ServerPort> serverPorts) {
        final ImmutableMap.Builder<String, LongAdder> pendingHttp1RequestsBuilder = ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> pendingHttp2RequestsBuilder = ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> activeHttp1WebSocketRequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> activeHttp1RequestsBuilder = ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> activeHttp2RequestsBuilder = ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> activeConnectionsBuilder = ImmutableMap.builder();
        serverPorts.forEach(port -> {
            final InetSocketAddress localAddress = port.localAddress();
            if (!(localAddress instanceof DomainSocketAddress)) {
                return;
            }
            final String path = ((DomainSocketAddress) localAddress).path();
            pendingHttp1RequestsBuilder.put(path, new LongAdder());
            pendingHttp2RequestsBuilder.put(path, new LongAdder());
            activeHttp1WebSocketRequestsBuilder.put(path, new LongAdder());
            activeHttp1RequestsBuilder.put(path, new LongAdder());
            activeHttp2RequestsBuilder.put(path, new LongAdder());
            activeConnectionsBuilder.put(path, new LongAdder());
        });
        pendingHttp1Requests = pendingHttp1RequestsBuilder.build();
        pendingHttp2Requests = pendingHttp2RequestsBuilder.build();
        activeHttp1WebSocketRequests = activeHttp1WebSocketRequestsBuilder.build();
        activeHttp1Requests = activeHttp1RequestsBuilder.build();
        activeHttp2Requests = activeHttp2RequestsBuilder.build();
        activeConnections = activeConnectionsBuilder.build();
    }

    @Override
    public void addActivePort(ServerPort actualPort) {
        // Do nothing because the port is already added in the constructor.
    }

    @Override
    public long pendingHttp1Requests() {
        return sum(pendingHttp1Requests);
    }

    private static long sum(Map<String, LongAdder> map) {
        return map.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    @Override
    public long pendingHttp2Requests() {
        return sum(pendingHttp2Requests);
    }

    @Override
    public long activeHttp1WebSocketRequests() {
        return sum(activeHttp1WebSocketRequests);
    }

    @Override
    public long activeHttp1Requests() {
        return sum(activeHttp1Requests);
    }

    @Override
    public long activeHttp2Requests() {
        return sum(activeHttp2Requests);
    }

    @Override
    public long activeConnections() {
        return sum(activeConnections);
    }

    @Override
    public void increasePendingHttp1Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp1Requests, true);
    }

    private static void increaseOrDecreaseRequests(InetSocketAddress socketAddress,
                                                   Map<String, LongAdder> requests,
                                                   boolean increase) {
        assert socketAddress instanceof DomainSocketAddress;
        final String path = ((DomainSocketAddress) socketAddress).path();
        final LongAdder longAdder = requests.get(path);
        assert longAdder != null;
        if (increase) {
            longAdder.increment();
        } else {
            longAdder.decrement();
        }
    }

    @Override
    public void decreasePendingHttp1Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp1Requests, false);
    }

    @Override
    public void increasePendingHttp2Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp2Requests, true);
    }

    @Override
    public void decreasePendingHttp2Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp2Requests, false);
    }

    @Override
    public void increaseActiveHttp1Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1Requests, true);
    }

    @Override
    public void decreaseActiveHttp1Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1Requests, false);
    }

    @Override
    public void increaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1WebSocketRequests, true);
    }

    @Override
    public void decreaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1WebSocketRequests, false);
    }

    @Override
    public void increaseActiveHttp2Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp2Requests, true);
    }

    @Override
    public void decreaseActiveHttp2Requests(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp2Requests, false);
    }

    @Override
    public void increaseActiveConnections(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeConnections, true);
    }

    @Override
    public void decreaseActiveConnections(InetSocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeConnections, false);
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        pendingHttp1Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        pendingHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        activeHttp1WebSocketRequests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1.websocket"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp1Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeConnections.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", port);
            meterRegistry.gauge("ALL_CONNECTIONS_METER_NAME", ImmutableList.of(portTag), value);
        });
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("pendingHttp1Requests", pendingHttp1Requests)
                          .add("activeHttp1WebSocketRequests", activeHttp1WebSocketRequests)
                          .add("activeHttp1Requests", activeHttp1Requests)
                          .add("pendingHttp2Requests", pendingHttp2Requests)
                          .add("activeHttp2Requests", activeHttp2Requests)
                          .add("activeConnections", activeConnections)
                          .toString();
    }
}

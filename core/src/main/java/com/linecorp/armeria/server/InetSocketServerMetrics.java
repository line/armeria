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

import static com.linecorp.armeria.server.DefaultServerMetrics.ALL_CONNECTIONS_METER_NAME;
import static com.linecorp.armeria.server.DefaultServerMetrics.ALL_REQUESTS_METER_NAME;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.DomainSocketAddress;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

final class InetSocketServerMetrics implements ServerMetrics {

    private final boolean hasEphemeralPort;

    private final Map<Integer, LongAdder> pendingHttp1Requests;
    private final Map<Integer, LongAdder> pendingHttp2Requests;
    private final Map<Integer, LongAdder> activeHttp1WebSocketRequests;
    private final Map<Integer, LongAdder> activeHttp1Requests;
    private final Map<Integer, LongAdder> activeHttp2Requests;
    private final Map<Integer, LongAdder> activeConnections;

    InetSocketServerMetrics(Iterable<ServerPort> serverPorts) {
        boolean hasEphemeralPort = false;
        final Map<Integer, LongAdder> pendingHttp1Requests = new Int2ObjectOpenHashMap<>();
        final Map<Integer, LongAdder> pendingHttp2Requests = new Int2ObjectOpenHashMap<>();
        final Map<Integer, LongAdder> activeHttp1WebSocketRequests = new Int2ObjectOpenHashMap<>();
        final Map<Integer, LongAdder> activeHttp1Requests = new Int2ObjectOpenHashMap<>();
        final Map<Integer, LongAdder> activeHttp2Requests = new Int2ObjectOpenHashMap<>();
        final Map<Integer, LongAdder> activeConnections = new Int2ObjectOpenHashMap<>();
        for (ServerPort serverPort : serverPorts) {
            final InetSocketAddress localAddress = serverPort.localAddress();
            if (!(localAddress instanceof DomainSocketAddress)) {
                final int port = localAddress.getPort();
                if (port == 0) {
                    hasEphemeralPort = true;
                } else {
                    pendingHttp1Requests.put(port, new LongAdder());
                    pendingHttp2Requests.put(port, new LongAdder());
                    activeHttp1WebSocketRequests.put(port, new LongAdder());
                    activeHttp1Requests.put(port, new LongAdder());
                    activeHttp2Requests.put(port, new LongAdder());
                    activeConnections.put(port, new LongAdder());
                }
            }
        }
        // Put a dummy value to avoid NPE in case where an unknown address is returned from the channel.
        // See the commit description of https://github.com/line/armeria/pull/5096
        pendingHttp1Requests.put(1, new LongAdder());
        pendingHttp2Requests.put(1, new LongAdder());
        activeHttp1WebSocketRequests.put(1, new LongAdder());
        activeHttp1Requests.put(1, new LongAdder());
        activeHttp2Requests.put(1, new LongAdder());
        activeConnections.put(1, new LongAdder());

        this.hasEphemeralPort = hasEphemeralPort;
        if (hasEphemeralPort) {
            // This is mostly for testing if the server has an ephemeral port.
            this.pendingHttp1Requests = new ConcurrentHashMap<>(pendingHttp1Requests);
            this.pendingHttp2Requests = new ConcurrentHashMap<>(pendingHttp2Requests);
            this.activeHttp1WebSocketRequests = new ConcurrentHashMap<>(activeHttp1WebSocketRequests);
            this.activeHttp1Requests = new ConcurrentHashMap<>(activeHttp1Requests);
            this.activeHttp2Requests = new ConcurrentHashMap<>(activeHttp2Requests);
            this.activeConnections = new ConcurrentHashMap<>(activeConnections);
        } else {
            this.pendingHttp1Requests = pendingHttp1Requests;
            this.pendingHttp2Requests = pendingHttp2Requests;
            this.activeHttp1WebSocketRequests = activeHttp1WebSocketRequests;
            this.activeHttp1Requests = activeHttp1Requests;
            this.activeHttp2Requests = activeHttp2Requests;
            this.activeConnections = activeConnections;
        }
    }

    @Override
    public void addActivePort(ServerPort actualPort) {
        if (!hasEphemeralPort) {
            return;
        }

        final InetSocketAddress address = actualPort.localAddress();
        final int port = address.getPort();
        if (pendingHttp1Requests.containsKey(port)) {
            return;
        }

        pendingHttp1Requests.put(port, new LongAdder());
        pendingHttp2Requests.put(port, new LongAdder());
        activeHttp1WebSocketRequests.put(port, new LongAdder());
        activeHttp1Requests.put(port, new LongAdder());
        activeHttp2Requests.put(port, new LongAdder());
        activeConnections.put(port, new LongAdder());
    }

    @Override
    public long pendingHttp1Requests() {
        return sum(pendingHttp1Requests);
    }

    private static long sum(Map<Integer, LongAdder> map) {
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
                                                   Map<Integer, LongAdder> requests,
                                                   boolean increase) {
        final LongAdder longAdder = requests.get(socketAddress.getPort());
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
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        pendingHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        activeHttp1WebSocketRequests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1.websocket"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp1Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_REQUESTS_METER_NAME,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeConnections.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(ALL_CONNECTIONS_METER_NAME, ImmutableList.of(portTag), value);
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

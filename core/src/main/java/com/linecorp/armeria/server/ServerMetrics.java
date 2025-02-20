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

package com.linecorp.armeria.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.DomainSocketAddress;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * A class that holds metrics related server.
 */
@UnstableApi
public final class ServerMetrics implements MeterBinder {

    private static final Logger logger = LoggerFactory.getLogger(ServerMetrics.class);

    private static boolean warnedInvalidSocketAddress;

    private final Iterable<ServerPort> ports;
    private final boolean hasEphemeralPort;

    private final Map<Integer, LongAdder> pendingHttp1Requests;
    private final Map<Integer, LongAdder> pendingHttp2Requests;
    private final Map<Integer, LongAdder> activeHttp1WebSocketRequests;
    private final Map<Integer, LongAdder> activeHttp1Requests;
    private final Map<Integer, LongAdder> activeHttp2Requests;
    private final Map<String, LongAdder> domainSocketPendingHttp1Requests;
    private final Map<String, LongAdder> domainSocketPendingHttp2Requests;
    private final Map<String, LongAdder> domainSocketActiveHttp1WebSocketRequests;
    private final Map<String, LongAdder> domainSocketActiveHttp1Requests;
    private final Map<String, LongAdder> domainSocketActiveHttp2Requests;

    /**
     * AtomicInteger is used to read the number of active connections frequently.
     */
    private final Map<Integer, AtomicInteger> activeConnections;
    private final Map<String, AtomicInteger> domainSocketActiveConnections;

    ServerMetrics(Iterable<ServerPort> ports) {
        this.ports = ports;

        final ImmutableMap.Builder<Integer, LongAdder> pendingHttp1RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<Integer, LongAdder> pendingHttp2RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<Integer, LongAdder> activeHttp1WebSocketRequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<Integer, LongAdder> activeHttp1RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<Integer, LongAdder> activeHttp2RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> domainSocketPendingHttp1RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> domainSocketPendingHttp2RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> domainSocketActiveHttp1WebSocketRequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> domainSocketActiveHttp1RequestsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, LongAdder> domainSocketActiveHttp2RequestsBuilder =
                ImmutableMap.builder();

        final ImmutableMap.Builder<Integer, AtomicInteger> ActiveConnectionsBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, AtomicInteger> domainSocketActiveConnectionsBuilder =
                ImmutableMap.builder();

        boolean hasEphemeralPort = false;
        for (ServerPort serverPort : ports) {
            final InetSocketAddress address = serverPort.localAddress();
            final int port = address.getPort();
            if (port == 0) {
                hasEphemeralPort = true;
            } else if (address instanceof DomainSocketAddress) {
                final String path = ((DomainSocketAddress) address).path();
                domainSocketPendingHttp1RequestsBuilder.put(path, new LongAdder());
                domainSocketPendingHttp2RequestsBuilder.put(path, new LongAdder());
                domainSocketActiveHttp1WebSocketRequestsBuilder.put(path, new LongAdder());
                domainSocketActiveHttp1RequestsBuilder.put(path, new LongAdder());
                domainSocketActiveHttp2RequestsBuilder.put(path, new LongAdder());
                domainSocketActiveConnectionsBuilder.put(path, new AtomicInteger());
            } else {
                pendingHttp1RequestsBuilder.put(port, new LongAdder());
                pendingHttp2RequestsBuilder.put(port, new LongAdder());
                activeHttp1WebSocketRequestsBuilder.put(port, new LongAdder());
                activeHttp1RequestsBuilder.put(port, new LongAdder());
                activeHttp2RequestsBuilder.put(port, new LongAdder());
                ActiveConnectionsBuilder.put(port, new AtomicInteger());
            }
        }
        // Put a dummy value to avoid NPE in case where an unknown address is returned from the channel.
        // See the commit description of https://github.com/line/armeria/pull/5096
        pendingHttp1RequestsBuilder.put(1, new LongAdder());
        pendingHttp2RequestsBuilder.put(1, new LongAdder());
        activeHttp1WebSocketRequestsBuilder.put(1, new LongAdder());
        activeHttp1RequestsBuilder.put(1, new LongAdder());
        activeHttp2RequestsBuilder.put(1, new LongAdder());
        ActiveConnectionsBuilder.put(1, new AtomicInteger());

        this.hasEphemeralPort = hasEphemeralPort;

        final Map<String, LongAdder> domainSocketPendingHttp1Requests =
                domainSocketPendingHttp1RequestsBuilder.build();
        final Map<String, LongAdder> domainSocketPendingHttp2Requests =
                domainSocketPendingHttp2RequestsBuilder.build();
        final Map<String, LongAdder> domainSocketActiveHttp1WebSocketRequests =
                domainSocketActiveHttp1WebSocketRequestsBuilder.build();
        final Map<String, LongAdder> domainSocketActiveHttp1Requests =
                domainSocketActiveHttp1RequestsBuilder.build();
        final Map<String, LongAdder> domainSocketActiveHttp2Requests =
                domainSocketActiveHttp2RequestsBuilder.build();
        final Map<String, AtomicInteger> domainSocketActiveConnections =
                domainSocketActiveConnectionsBuilder.build();

        final Map<Integer, LongAdder> pendingHttp1Requests = pendingHttp1RequestsBuilder.build();
        final Map<Integer, LongAdder> pendingHttp2Requests = pendingHttp2RequestsBuilder.build();
        final Map<Integer, LongAdder> activeHttp1WebSocketRequests =
                activeHttp1WebSocketRequestsBuilder.build();
        final Map<Integer, LongAdder> activeHttp1Requests = activeHttp1RequestsBuilder.build();
        final Map<Integer, LongAdder> activeHttp2Requests = activeHttp2RequestsBuilder.build();
        final Map<Integer, AtomicInteger> activeConnections = ActiveConnectionsBuilder.build();

        if (!hasEphemeralPort) {
            this.domainSocketPendingHttp1Requests =
                    new Object2ObjectOpenHashMap<>(domainSocketPendingHttp1Requests);
            this.domainSocketPendingHttp2Requests =
                    new Object2ObjectOpenHashMap<>(domainSocketPendingHttp2Requests);
            this.domainSocketActiveHttp1WebSocketRequests =
                    new Object2ObjectOpenHashMap<>(domainSocketActiveHttp1WebSocketRequests);
            this.domainSocketActiveHttp1Requests =
                    new Object2ObjectOpenHashMap<>(domainSocketActiveHttp1Requests);
            this.domainSocketActiveHttp2Requests =
                    new Object2ObjectOpenHashMap<>(domainSocketActiveHttp2Requests);
            this.domainSocketActiveConnections =
                    new Object2ObjectOpenHashMap<>(domainSocketActiveConnections);

            this.pendingHttp1Requests = new Int2ObjectOpenHashMap<>(pendingHttp1Requests);
            this.pendingHttp2Requests = new Int2ObjectOpenHashMap<>(pendingHttp2Requests);
            this.activeHttp1WebSocketRequests = new Int2ObjectOpenHashMap<>(activeHttp1WebSocketRequests);
            this.activeHttp1Requests = new Int2ObjectOpenHashMap<>(activeHttp1Requests);
            this.activeHttp2Requests = new Int2ObjectOpenHashMap<>(activeHttp2Requests);
            this.activeConnections = new Int2ObjectOpenHashMap<>(activeConnections);
        } else {
            // This is mostly for testing if the server has an ephemeral port.
            this.domainSocketPendingHttp1Requests =
                    new ConcurrentHashMap<>(domainSocketPendingHttp1Requests);
            this.domainSocketPendingHttp2Requests =
                    new ConcurrentHashMap<>(domainSocketPendingHttp2Requests);
            this.domainSocketActiveHttp1WebSocketRequests =
                    new ConcurrentHashMap<>(domainSocketActiveHttp1WebSocketRequests);
            this.domainSocketActiveHttp1Requests =
                    new ConcurrentHashMap<>(domainSocketActiveHttp1Requests);
            this.domainSocketActiveHttp2Requests =
                    new ConcurrentHashMap<>(domainSocketActiveHttp2Requests);
            this.domainSocketActiveConnections =
                    new ConcurrentHashMap<>(domainSocketActiveConnections);

            this.pendingHttp1Requests = new ConcurrentHashMap<>(pendingHttp1Requests);
            this.pendingHttp2Requests = new ConcurrentHashMap<>(pendingHttp2Requests);
            this.activeHttp1WebSocketRequests = new ConcurrentHashMap<>(activeHttp1WebSocketRequests);
            this.activeHttp1Requests = new ConcurrentHashMap<>(activeHttp1Requests);
            this.activeHttp2Requests = new ConcurrentHashMap<>(activeHttp2Requests);
            this.activeConnections = new ConcurrentHashMap<>(activeConnections);
        }
    }

    void addActivePort(ServerPort actualPort) {
        if (!hasEphemeralPort) {
            return;
        }

        final InetSocketAddress address = actualPort.localAddress();
        if (address instanceof DomainSocketAddress) {
            final String path = ((DomainSocketAddress) address).path();
            if (domainSocketPendingHttp1Requests.containsKey(path)) {
                return;
            }

            domainSocketPendingHttp1Requests.put(path, new LongAdder());
            domainSocketPendingHttp2Requests.put(path, new LongAdder());
            domainSocketActiveHttp1WebSocketRequests.put(path, new LongAdder());
            domainSocketActiveHttp1Requests.put(path, new LongAdder());
            domainSocketActiveHttp2Requests.put(path, new LongAdder());
            domainSocketActiveConnections.put(path, new AtomicInteger());
        } else {
            final int port = address.getPort();
            if (pendingHttp1Requests.containsKey(port)) {
                return;
            }

            pendingHttp1Requests.put(port, new LongAdder());
            pendingHttp2Requests.put(port, new LongAdder());
            activeHttp1WebSocketRequests.put(port, new LongAdder());
            activeHttp1Requests.put(port, new LongAdder());
            activeHttp2Requests.put(port, new LongAdder());
            activeConnections.put(port, new AtomicInteger());
        }
    }

    /**
     * Returns the number of all pending requests.
     */
    public long pendingRequests() {
        return pendingHttp1Requests() + pendingHttp2Requests();
    }

    /**
     * Returns the number of pending http1 requests.
     */
    public long pendingHttp1Requests() {
        return pendingHttp1Requests.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    /**
     * Returns the number of pending http2 requests.
     */
    public long pendingHttp2Requests() {
        return pendingHttp2Requests.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    /**
     * Returns the number of all active requests.
     */
    public long activeRequests() {
        return activeHttp1WebSocketRequests() +
               activeHttp1Requests() +
               activeHttp2Requests();
    }

    /**
     * Returns the number of active http1 web socket requests.
     */
    public long activeHttp1WebSocketRequests() {
        return activeHttp1WebSocketRequests.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    /**
     * Returns the number of active http1 requests.
     */
    public long activeHttp1Requests() {
        return activeHttp1Requests.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    /**
     * Returns the number of active http2 requests.
     */
    public long activeHttp2Requests() {
        return activeHttp2Requests.values().stream().mapToLong(LongAdder::longValue).sum();
    }

    /**
     * Returns the number of open connections.
     */
    public int activeConnections() {
        return activeConnections.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    void increasePendingHttp1Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp1Requests, domainSocketPendingHttp1Requests, true);
    }

    private static void increaseOrDecreaseRequests(SocketAddress socketAddress,
                                                   Map<Integer, LongAdder> pendingHttp1Requests,
                                                   Map<String, LongAdder> domainSocketPendingHttp1Requests,
                                                   boolean increase) {
        if (socketAddress instanceof DomainSocketAddress) {
            final String path = ((DomainSocketAddress) socketAddress).path();
            final LongAdder longAdder = domainSocketPendingHttp1Requests.get(path);
            assert longAdder != null;
            if (increase) {
                longAdder.increment();
            } else {
                longAdder.decrement();
            }
        } else if (socketAddress instanceof InetSocketAddress) {
            final int port = ((InetSocketAddress) socketAddress).getPort();
            final LongAdder longAdder = pendingHttp1Requests.get(port);
            assert longAdder != null;
            if (increase) {
                longAdder.increment();
            } else {
                longAdder.decrement();
            }
        }
    }

    void decreasePendingHttp1Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp1Requests,
                                   domainSocketPendingHttp1Requests, false);
    }

    void increasePendingHttp2Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp2Requests, domainSocketPendingHttp2Requests, true);
    }

    void decreasePendingHttp2Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, pendingHttp2Requests,
                                   domainSocketPendingHttp2Requests, false);
    }

    void increaseActiveHttp1Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1Requests, domainSocketActiveHttp1Requests, true);
    }

    void decreaseActiveHttp1Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1Requests, domainSocketActiveHttp1Requests, false);
    }

    void increaseActiveHttp1WebSocketRequests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1WebSocketRequests,
                                   domainSocketActiveHttp1WebSocketRequests, true);
    }

    void decreaseActiveHttp1WebSocketRequests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp1WebSocketRequests,
                                   domainSocketActiveHttp1WebSocketRequests, false);
    }

    void increaseActiveHttp2Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp2Requests, domainSocketActiveHttp2Requests, true);
    }

    void decreaseActiveHttp2Requests(SocketAddress socketAddress) {
        increaseOrDecreaseRequests(socketAddress, activeHttp2Requests, domainSocketActiveHttp2Requests, false);
    }

    void increaseActiveConnections(SocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            final String path = ((DomainSocketAddress) socketAddress).path();
            final AtomicInteger atomicInteger = domainSocketActiveConnections.get(path);
            assert atomicInteger != null;
            atomicInteger.incrementAndGet();
        } else if (socketAddress instanceof InetSocketAddress) {
            final int port = ((InetSocketAddress) socketAddress).getPort();
            final AtomicInteger atomicInteger = activeConnections.get(port);
            assert atomicInteger != null;
            atomicInteger.incrementAndGet();
        } else {
            if (!warnedInvalidSocketAddress) {
                warnedInvalidSocketAddress = true;
                logger.warn("Unexpected address type: {}", socketAddress.getClass().getName());
            }
        }
    }

    void decreaseActiveConnections(SocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            final String path = ((DomainSocketAddress) socketAddress).path();
            final AtomicInteger atomicInteger = domainSocketActiveConnections.get(path);
            assert atomicInteger != null;
            atomicInteger.decrementAndGet();
        } else if (socketAddress instanceof InetSocketAddress) {
            final int port = ((InetSocketAddress) socketAddress).getPort();
            final AtomicInteger atomicInteger = activeConnections.get(port);
            assert atomicInteger != null;
            atomicInteger.decrementAndGet();
        }
        // already warned in increaseActiveConnectionsAndGet
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        final String allRequestsMeterName = "armeria.server.all.requests";
        pendingHttp1Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        pendingHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        activeHttp1WebSocketRequests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1.websocket"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp1Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeHttp2Requests.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "active")),
                                value);
        });
        activeConnections.forEach((port, value) -> {
            final Tag portTag = Tag.of("port", String.valueOf(port));
            meterRegistry.gauge("armeria.server.connections", ImmutableList.of(portTag), value);
        });

        domainSocketPendingHttp1Requests.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        domainSocketPendingHttp2Requests.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "pending")),
                                value);
        });
        domainSocketActiveHttp1WebSocketRequests.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1.websocket"),
                                                 Tag.of("state", "active")),
                                value);
        });
        domainSocketActiveHttp1Requests.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http1"),
                                                 Tag.of("state", "active")),
                                value);
        });
        domainSocketActiveHttp2Requests.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName,
                                ImmutableList.of(portTag, Tag.of("protocol", "http2"),
                                                 Tag.of("state", "active")),
                                value);
        });
        domainSocketActiveConnections.forEach((path, value) -> {
            final Tag portTag = Tag.of("port", path);
            meterRegistry.gauge(allRequestsMeterName, ImmutableList.of(portTag), value);
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
                          .add("domainSocketPendingHttp1Requests", domainSocketPendingHttp1Requests)
                          .add("domainSocketPendingHttp2Requests", domainSocketPendingHttp2Requests)
                          .add("domainSocketActiveHttp1WebSocketRequests",
                               domainSocketActiveHttp1WebSocketRequests)
                          .add("domainSocketActiveHttp1Requests", domainSocketActiveHttp1Requests)
                          .add("domainSocketActiveHttp2Requests", domainSocketActiveHttp2Requests)
                          .add("domainSocketActiveConnections", domainSocketActiveConnections)
                          .toString();
    }
}

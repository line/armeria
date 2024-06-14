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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * A class that holds metrics related server.
 */
@UnstableApi
public final class ServerMetrics implements MeterBinder {

    private final LongAdder pendingHttp1Requests = new LongAdder();
    private final LongAdder pendingHttp2Requests = new LongAdder();
    private final LongAdder activeHttp1WebSocketRequests = new LongAdder();
    private final LongAdder activeHttp1Requests = new LongAdder();
    private final LongAdder activeHttp2Requests = new LongAdder();

    /**
     * AtomicInteger is used to read the number of active connections frequently.
     */
    private final AtomicInteger activeConnections = new AtomicInteger();

    ServerMetrics() {}

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
        return pendingHttp1Requests.longValue();
    }

    /**
     * Returns the number of pending http2 requests.
     */
    public long pendingHttp2Requests() {
        return pendingHttp2Requests.longValue();
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
        return activeHttp1WebSocketRequests.longValue();
    }

    /**
     * Returns the number of active http1 requests.
     */
    public long activeHttp1Requests() {
        return activeHttp1Requests.longValue();
    }

    /**
     * Returns the number of active http2 requests.
     */
    public long activeHttp2Requests() {
        return activeHttp2Requests.longValue();
    }

    /**
     * Returns the number of open connections.
     */
    public int activeConnections() {
        return activeConnections.get();
    }

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

    void increaseActiveHttp1Requests() {
        activeHttp1Requests.increment();
    }

    void decreaseActiveHttp1Requests() {
        activeHttp1Requests.decrement();
    }

    void increaseActiveHttp1WebSocketRequests() {
        activeHttp1WebSocketRequests.increment();
    }

    void decreaseActiveHttp1WebSocketRequests() {
        activeHttp1WebSocketRequests.decrement();
    }

    void increaseActiveHttp2Requests() {
        activeHttp2Requests.increment();
    }

    void decreaseActiveHttp2Requests() {
        activeHttp2Requests.decrement();
    }

    int increaseActiveConnectionsAndGet() {
        return activeConnections.incrementAndGet();
    }

    void decreaseActiveConnections() {
        activeConnections.decrementAndGet();
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        meterRegistry.gauge("armeria.server.connections", activeConnections);
        // pending requests
        meterRegistry.gauge("armeria.server.pending.requests",
                            ImmutableList.of(Tag.of("protocol", "http1")), pendingHttp1Requests);
        meterRegistry.gauge("armeria.server.pending.requests",
                            ImmutableList.of(Tag.of("protocol", "http2")), pendingHttp2Requests);
        // Active requests
        meterRegistry.gauge("armeria.server.active.requests", ImmutableList.of(Tag.of("protocol", "http1")),
                            activeHttp1Requests);
        meterRegistry.gauge("armeria.server.active.requests", ImmutableList.of(Tag.of("protocol", "http2")),
                            activeHttp2Requests);
        meterRegistry.gauge("armeria.server.active.requests",
                            ImmutableList.of(Tag.of("protocol", "http1.websocket")),
                            activeHttp1WebSocketRequests);
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

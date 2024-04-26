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

/**
 * A class that holds metrics related server.
 */
public final class ServerMetrics {

    private final LongAdder pendingRequests = new LongAdder();
    private final LongAdder activeHttp1WebSocketRequests = new LongAdder();
    private final LongAdder activeHttp1Requests = new LongAdder();
    private final LongAdder activeHttp2Requests = new LongAdder();

    /**
     * AtomicInteger is used to read the number of active connections frequently.
     */
    private final AtomicInteger activeConnections = new AtomicInteger();

    ServerMetrics() {
    }

    /**
     * Returns the number of pending requests.
     */
    public long pendingRequests() {
        return pendingRequests.longValue();
    }

    /**
     * Returns the number of all active requests.
     */
    public long activeRequests() {
        return activeHttp1WebSocketRequests.longValue() +
               activeHttp1Requests.longValue() +
               activeHttp2Requests.longValue();
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

    void increasePendingRequests() {
        pendingRequests.increment();
    }

    void decreasePendingRequests() {
        pendingRequests.decrement();
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
}

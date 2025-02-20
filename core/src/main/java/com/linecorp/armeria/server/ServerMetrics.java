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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * An interface that provides the requests and connections metrics information of the server.
 */
@UnstableApi
public interface ServerMetrics extends MeterBinder {

    /**
     * Adds the {@link ServerPort} to the {@link ServerMetrics}.
     */
    void addActivePort(ServerPort actualPort);

    /**
     * Returns the number of all pending requests.
     */
    default long pendingRequests() {
        return pendingHttp1Requests() + pendingHttp2Requests();
    }

    /**
     * Returns the number of pending http1 requests.
     */
    long pendingHttp1Requests();

    /**
     * Returns the number of pending http2 requests.
     */
    long pendingHttp2Requests();

    /**
     * Returns the number of all active requests.
     */
    default long activeRequests() {
        return activeHttp1WebSocketRequests() +
               activeHttp1Requests() +
               activeHttp2Requests();
    }

    /**
     * Returns the number of active http1 web socket requests.
     */
    long activeHttp1WebSocketRequests();

    /**
     * Returns the number of active http1 requests.
     */
    long activeHttp1Requests();

    /**
     * Returns the number of active http2 requests.
     */
    long activeHttp2Requests();

    /**
     * Returns the number of open connections.
     */
    long activeConnections();

    /**
     * Returns the number of all connections.
     */
    void increasePendingHttp1Requests(InetSocketAddress socketAddress);

    /**
     * Decreases the number of all pending http1 requests.
     */
    void decreasePendingHttp1Requests(InetSocketAddress socketAddress);

    /**
     * Increases the number of all pending http2 requests.
     */
    void increasePendingHttp2Requests(InetSocketAddress socketAddress);

    /**
     * Decreases the number of all pending http2 requests.
     */
    void decreasePendingHttp2Requests(InetSocketAddress socketAddress);

    /**
     * Increases the number of all active http1 requests.
     */
    void increaseActiveHttp1Requests(InetSocketAddress socketAddress);

    /**
     * Decreases the number of all active http1 requests.
     */
    void decreaseActiveHttp1Requests(InetSocketAddress socketAddress);

    /**
     * Increases the number of all active http1 web socket requests.
     */
    void increaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress);

    /**
     * Decreases the number of all active http1 web socket requests.
     */
    void decreaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress);

    /**
     * Increases the number of all active http2 requests.
     */
    void increaseActiveHttp2Requests(InetSocketAddress socketAddress);

    /**
     * Decreases the number of all active http2 requests.
     */
    void decreaseActiveHttp2Requests(InetSocketAddress socketAddress);

    /**
     * Increases the number of open connections.
     */
    void increaseActiveConnections(InetSocketAddress socketAddress);

    /**
     * Decreases the number of open connections.
     */
    void decreaseActiveConnections(InetSocketAddress socketAddress);
}

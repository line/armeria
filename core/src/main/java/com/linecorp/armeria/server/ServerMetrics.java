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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A class that holds metrics related server.
 */
@UnstableApi
public final class ServerMetrics {

    static final String ALL_REQUESTS_METER_NAME = "armeria.server.all.requests";
    static final String ALL_CONNECTIONS_METER_NAME = "armeria.server.connections";

    private final Set<ServerPortMetric> serverPortMetrics = new CopyOnWriteArraySet<>();
    private final MeterRegistry meterRegistry;

    ServerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void addServerPort(ServerPort serverPort) {
        final ServerPortMetric serverPortMetric = serverPort.serverPortMetric();
        assert serverPortMetric != null;
        if (serverPortMetrics.add(serverPortMetric)) {
            serverPortMetric.bindTo(meterRegistry, serverPort);
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
        return serverPortMetrics.stream().mapToLong(ServerPortMetric::pendingHttp1Requests).sum();
    }

    /**
     * Returns the number of pending http2 requests.
     */
    public long pendingHttp2Requests() {
        return serverPortMetrics.stream().mapToLong(ServerPortMetric::pendingHttp2Requests).sum();
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
        return serverPortMetrics.stream().mapToLong(ServerPortMetric::activeHttp1WebSocketRequests).sum();
    }

    /**
     * Returns the number of active http1 requests.
     */
    public long activeHttp1Requests() {
        return serverPortMetrics.stream().mapToLong(ServerPortMetric::activeHttp1Requests).sum();
    }

    /**
     * Returns the number of active http2 requests.
     */
    public long activeHttp2Requests() {
        return serverPortMetrics.stream().mapToLong(ServerPortMetric::activeHttp2Requests).sum();
    }

    /**
     * Returns the number of open connections.
     */
    public int activeConnections() {
        return Ints.saturatedCast(serverPortMetrics.stream().mapToLong(ServerPortMetric::activeConnections)
                                                   .sum());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serverPortMetrics", serverPortMetrics)
                          .toString();
    }
}

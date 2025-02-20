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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.DomainSocketAddress;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultServerMetrics implements ServerMetrics {

    static final String ALL_REQUESTS_METER_NAME = "armeria.server.all.requests";
    static final String ALL_CONNECTIONS_METER_NAME = "armeria.server.connections";

    private final InetSocketServerMetrics inetSocketServerMetrics;
    private final DomainSocketServerMetrics domainSocketServerMetrics;

    DefaultServerMetrics(Iterable<ServerPort> ports) {
        inetSocketServerMetrics = new InetSocketServerMetrics(ports);
        domainSocketServerMetrics = new DomainSocketServerMetrics(ports);
    }

    @Override
    public void addActivePort(ServerPort actualPort) {
        if (!(actualPort.localAddress() instanceof DomainSocketAddress)) {
            inetSocketServerMetrics.addActivePort(actualPort);
        }
    }

    @Override
    public long pendingHttp1Requests() {
        return inetSocketServerMetrics.pendingHttp1Requests() +
               domainSocketServerMetrics.pendingHttp1Requests();
    }

    @Override
    public long pendingHttp2Requests() {
        return inetSocketServerMetrics.pendingHttp2Requests() +
               domainSocketServerMetrics.pendingHttp2Requests();
    }

    @Override
    public long activeHttp1WebSocketRequests() {
        return inetSocketServerMetrics.activeHttp1WebSocketRequests() +
               domainSocketServerMetrics.activeHttp1WebSocketRequests();
    }

    @Override
    public long activeHttp1Requests() {
        return inetSocketServerMetrics.activeHttp1Requests() +
               domainSocketServerMetrics.activeHttp1Requests();
    }

    @Override
    public long activeHttp2Requests() {
        return inetSocketServerMetrics.activeHttp2Requests() +
               domainSocketServerMetrics.activeHttp2Requests();
    }

    @Override
    public long activeConnections() {
        return inetSocketServerMetrics.activeConnections() +
               domainSocketServerMetrics.activeConnections();
    }

    @Override
    public void increasePendingHttp1Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increasePendingHttp1Requests(socketAddress);
        } else {
            inetSocketServerMetrics.increasePendingHttp1Requests(socketAddress);
        }
    }

    @Override
    public void decreasePendingHttp1Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreasePendingHttp1Requests(socketAddress);
        } else {
            inetSocketServerMetrics.decreasePendingHttp1Requests(socketAddress);
        }
    }

    @Override
    public void increasePendingHttp2Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increasePendingHttp2Requests(socketAddress);
        } else {
            inetSocketServerMetrics.increasePendingHttp2Requests(socketAddress);
        }
    }

    @Override
    public void decreasePendingHttp2Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreasePendingHttp2Requests(socketAddress);
        } else {
            inetSocketServerMetrics.decreasePendingHttp2Requests(socketAddress);
        }
    }

    @Override
    public void increaseActiveHttp1Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increaseActiveHttp1Requests(socketAddress);
        } else {
            inetSocketServerMetrics.increaseActiveHttp1Requests(socketAddress);
        }
    }

    @Override
    public void decreaseActiveHttp1Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreaseActiveHttp1Requests(socketAddress);
        } else {
            inetSocketServerMetrics.decreaseActiveHttp1Requests(socketAddress);
        }
    }

    @Override
    public void increaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increaseActiveHttp1WebSocketRequests(socketAddress);
        } else {
            inetSocketServerMetrics.increaseActiveHttp1WebSocketRequests(socketAddress);
        }
    }

    @Override
    public void decreaseActiveHttp1WebSocketRequests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreaseActiveHttp1WebSocketRequests(socketAddress);
        } else {
            inetSocketServerMetrics.decreaseActiveHttp1WebSocketRequests(socketAddress);
        }
    }

    @Override
    public void increaseActiveHttp2Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increaseActiveHttp2Requests(socketAddress);
        } else {
            inetSocketServerMetrics.increaseActiveHttp2Requests(socketAddress);
        }
    }

    @Override
    public void decreaseActiveHttp2Requests(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreaseActiveHttp2Requests(socketAddress);
        } else {
            inetSocketServerMetrics.decreaseActiveHttp2Requests(socketAddress);
        }
    }

    @Override
    public void increaseActiveConnections(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.increaseActiveConnections(socketAddress);
        } else {
            inetSocketServerMetrics.increaseActiveConnections(socketAddress);
        }
    }

    @Override
    public void decreaseActiveConnections(InetSocketAddress socketAddress) {
        if (socketAddress instanceof DomainSocketAddress) {
            domainSocketServerMetrics.decreaseActiveConnections(socketAddress);
        } else {
            inetSocketServerMetrics.decreaseActiveConnections(socketAddress);
        }
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        inetSocketServerMetrics.bindTo(meterRegistry);
        domainSocketServerMetrics.bindTo(meterRegistry);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("inetSocketServerMetrics", inetSocketServerMetrics)
                          .add("domainSocketServerMetrics", domainSocketServerMetrics)
                          .toString();
    }
}

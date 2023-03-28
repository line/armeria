/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeMap;

final class MetricCollectingConnectionPoolListener implements ConnectionPoolListener {
    private final ConnectionPoolMetrics connectionPoolMetrics;

    MetricCollectingConnectionPoolListener(MeterRegistry registry, MeterIdPrefix idPrefix) {
        requireNonNull(registry, "registry");
        requireNonNull(idPrefix, "idPrefix");

        connectionPoolMetrics = new ConnectionPoolMetrics(registry, idPrefix);
    }

    @Override
    public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        connectionPoolMetrics.increaseConnOpened(protocol, remoteAddr, localAddr);
    }

    @Override
    public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        connectionPoolMetrics.increaseConnClosed(protocol, remoteAddr, localAddr);
    }
}

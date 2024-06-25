/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 *
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 *  software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND,
 *  either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.ConnectionEventState.KeepAliveState;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeMap;

final class MetricCollectingClientConnectionEventListener implements ClientConnectionEventListener {
    private final ClientConnectionEventMetrics clientConnectionEventMetrics;

    MetricCollectingClientConnectionEventListener(MeterRegistry registry, MeterIdPrefix idPrefix) {
        clientConnectionEventMetrics = new ClientConnectionEventMetrics(registry, idPrefix);
    }

    @Override
    public void connectionPending(SessionProtocol desiredProtocol,
                                  InetSocketAddress remoteAddress,
                                  InetSocketAddress localAddress,
                                  AttributeMap attrs) throws Exception {
        clientConnectionEventMetrics.pending(desiredProtocol, remoteAddress, localAddress);
    }

    @Override
    public void connectionFailed(SessionProtocol desiredProtocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 Throwable cause) throws Exception {
        clientConnectionEventMetrics.failed(desiredProtocol, remoteAddress, localAddress);
    }

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                                 SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {
        assert desiredProtocol != null;

        clientConnectionEventMetrics.opened(desiredProtocol, protocol, remoteAddress, localAddress);
    }

    @Override
    public void connectionActive(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean isNew) throws Exception {
        clientConnectionEventMetrics.active(protocol, remoteAddress, localAddress, isNew);
    }

    @Override
    public void connectionIdle(SessionProtocol protocol,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress,
                               AttributeMap attrs) throws Exception {
        clientConnectionEventMetrics.idle(protocol, remoteAddress, localAddress);
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 KeepAliveState keepAliveState) throws Exception {
        clientConnectionEventMetrics.closed(protocol, remoteAddress, localAddress, keepAliveState);
    }
}

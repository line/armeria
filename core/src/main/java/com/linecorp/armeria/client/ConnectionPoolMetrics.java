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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

final class ConnectionPoolMetrics {
    public static final String PROTOCOL = "protocol";
    public static final String REMOTE_ADDR = "remoteAddr";
    public static final String LOCAL_ADDR = "localAddr";
    public static final String STATE = "state";
    private Counter.Builder connectionOpened;
    private Counter.Builder connectionClosed;
    private MeterRegistry meterRegistry;

    ConnectionPoolMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(idPrefix, "idPrefix");

        this.meterRegistry = meterRegistry;
        initCounters(idPrefix);
    }

    void increaseConnOpened(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        Counter connOpenedCounter = connectionOpened.tags(PROTOCOL, protocol.name(),
                                                          REMOTE_ADDR, remoteAddr.getHostString(),
                                                          LOCAL_ADDR, localAddr.getHostString()).register(meterRegistry);
        connOpenedCounter.increment();
    }

    void increaseConnClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        Counter connClosedCounter = connectionClosed.tags(PROTOCOL, protocol.name(),
                                                          REMOTE_ADDR, remoteAddr.getHostString(),
                                                          LOCAL_ADDR, localAddr.getHostString()).register(meterRegistry);
        connClosedCounter.increment();
    }

    private void initCounters(MeterIdPrefix idPrefix) {
        connectionOpened = Counter.builder(idPrefix.name()).tags(STATE, "open");
        connectionClosed = Counter.builder(idPrefix.name()).tags(STATE, "closed");
    }
}

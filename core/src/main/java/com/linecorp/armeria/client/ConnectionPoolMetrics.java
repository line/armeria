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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

final class ConnectionPoolMetrics {
    private static final String PROTOCOL = "protocol";
    private static final String REMOTE_ADDR = "remote.ip";
    private static final String LOCAL_ADDR = "local.ip";
    private static final String STATE = "state";
    private MeterRegistry meterRegistry;
    private MeterIdPrefix idPrefix;

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    ConnectionPoolMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(idPrefix, "idPrefix");

        this.idPrefix = idPrefix;
        this.meterRegistry = meterRegistry;
    }

    void increaseConnOpened(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        meterRegistry.counter(idPrefix.name(),
                              idPrefix.tags(PROTOCOL, protocol.name(),
                                            REMOTE_ADDR, remoteAddr.getAddress().getHostAddress(),
                                            LOCAL_ADDR, localAddr.getAddress().getHostAddress(),
                                            STATE, "open"))
                     .increment();
    }

    void increaseConnClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        meterRegistry.counter(idPrefix.name(),
                              idPrefix.tags(
                                      PROTOCOL, protocol.name(),
                                      REMOTE_ADDR, remoteAddr.getAddress().getHostAddress(),
                                      LOCAL_ADDR, localAddr.getAddress().getHostAddress(),
                                      STATE, "closed"))
                     .increment();
    }
}

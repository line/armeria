/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeMap;

public class CountingClientConnectionEventListener implements ClientConnectionEventListener {
    private final Map<String, Integer> counter = new ConcurrentHashMap<>();

    private void increase(String key) {
        counter.compute(key, (unused, counter) -> counter == null ? 1 : counter + 1);
    }

    @Override
    public void connectionPending(SessionProtocol desiredProtocol,
                                  InetSocketAddress remoteAddress,
                                  InetSocketAddress localAddress,
                                  AttributeMap attrs) throws Exception {
        increase(key("pending", desiredProtocol));
    }

    @Override
    public void connectionFailed(SessionProtocol desiredProtocol,
                                 InetSocketAddress remoteAddress,
                                 @Nullable InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 Throwable cause,
                                 boolean wasPending) throws Exception {
        increase(key("failed", desiredProtocol));
    }

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                                 SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {
        increase(key("opened", protocol));
    }

    @Override
    public void connectionActive(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean wasIdle) throws Exception {
        increase(key("active", protocol));
    }

    @Override
    public void connectionIdle(SessionProtocol protocol,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress,
                               AttributeMap attrs) throws Exception {
        increase(key("idle", protocol));
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean wasIdle) throws Exception {
        increase(key("closed", protocol));
    }

    public int pending(SessionProtocol desiredProtocol) {
        return counter.getOrDefault(key("pending", desiredProtocol), 0);
    }

    public int failed(SessionProtocol desiredProtocol) {
        return counter.getOrDefault(key("failed", desiredProtocol), 0);
    }

    public int opened(SessionProtocol protocol) {
        return counter.getOrDefault(key("opened", protocol), 0);
    }

    public int closed(SessionProtocol protocol) {
        return counter.getOrDefault(key("closed", protocol), 0);
    }

    public int active(SessionProtocol protocol) {
        return counter.getOrDefault(key("active", protocol), 0);
    }

    public int idle(SessionProtocol protocol) {
        return counter.getOrDefault(key("idle", protocol), 0);
    }

    private static String key(String eventType, SessionProtocol protocol) {
        return eventType + "-" + protocol.uriText();
    }
}

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ConnectionEventState.KeepAliveState;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.util.AttributeMap;

public class CountingClientConnectionEventListener implements ClientConnectionEventListener {
    @GuardedBy("lock")
    private final Map<List<String>, Integer> counter = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();

    @Override
    public void connectionPending(SessionProtocol desiredProtocol,
                                  InetSocketAddress remoteAddress,
                                  InetSocketAddress localAddress,
                                  AttributeMap attrs) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("pending", desiredProtocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionFailed(SessionProtocol desiredProtocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 Throwable cause) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("failed", desiredProtocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                                 SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("opened", protocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionActive(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean isNew) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("active", protocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionIdle(SessionProtocol protocol,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress,
                               AttributeMap attrs) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("idle", protocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 KeepAliveState isActive) throws Exception {
        try {
            lock.lock();
            final List<String> key = key("closed", protocol);
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        } finally {
            lock.unlock();
        }
    }

    public int pending(SessionProtocol desiredProtocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("pending", desiredProtocol), 0);
        } finally {
            lock.unlock();
        }
    }

    public int failed(SessionProtocol desiredProtocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("failed", desiredProtocol), 0);
        } finally {
            lock.unlock();
        }
    }

    public int opened(SessionProtocol protocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("opened", protocol), 0);
        } finally {
            lock.unlock();
        }
    }

    public int closed(SessionProtocol protocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("closed", protocol), 0);
        } finally {
            lock.unlock();
        }
    }

    public int active(SessionProtocol protocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("active", protocol), 0);
        } finally {
            lock.unlock();
        }
    }

    public int idle(SessionProtocol protocol) {
        try {
            lock.lock();
            return counter.getOrDefault(key("idle", protocol), 0);
        } finally {
            lock.unlock();
        }
    }

    private static List<String> key(String eventType, SessionProtocol protocol) {
        return Arrays.asList(eventType, protocol.uriText());
    }
}

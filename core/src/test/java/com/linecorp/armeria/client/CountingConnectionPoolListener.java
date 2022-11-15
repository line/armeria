/*
 * Copyright 2022 LINE Corporation
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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.AttributeMap;

/**
 * A {@link ConnectionPoolListener} to count the number of connections which have been open and closed.
 */
public final class CountingConnectionPoolListener implements ConnectionPoolListener {

    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    public int opened() {
        return opened.get();
    }

    public int closed() {
        return closed.get();
    }

    @Override
    public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        opened.incrementAndGet();
    }

    @Override
    public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        closed.incrementAndGet();
    }
}

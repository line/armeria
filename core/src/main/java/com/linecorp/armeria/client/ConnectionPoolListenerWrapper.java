/*
 * Copyright 2018 LINE Corporation
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

import io.netty.util.AttributeMap;

/**
 * A {@link ConnectionPoolListener} that wraps an existing {@link ConnectionPoolListener}.
 */
public class ConnectionPoolListenerWrapper implements ConnectionPoolListener {

    private final ConnectionPoolListener delegate;

    /**
     * Creates a new instance with the specified {@code delegate}.
     */
    protected ConnectionPoolListenerWrapper(ConnectionPoolListener delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link ConnectionPoolListener} this handler decorates.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends ConnectionPoolListener> T delegate() {
        return (T) delegate;
    }

    @Override
    public void connectionOpen(SessionProtocol protocol,
                               InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr,
                               AttributeMap attrs) throws Exception {
        delegate().connectionOpen(protocol, remoteAddr, localAddr, attrs);
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr,
                                 AttributeMap attrs) throws Exception {
        delegate().connectionClosed(protocol, remoteAddr, localAddr, attrs);
    }
}

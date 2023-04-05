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

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Ticker;
import com.linecorp.armeria.common.util.Unwrappable;

import io.netty.util.AttributeMap;

/**
 * Listens to the client connection pool events.
 */
public interface ConnectionPoolListener extends Unwrappable {

    /**
     * Returns an instance that does nothing.
     */
    static ConnectionPoolListener noop() {
        return ConnectionPoolListenerAdapter.NOOP;
    }

    /**
     * Returns a {@link ConnectionPoolListener} that logs the connection pool events.
     */
    static ConnectionPoolListener logging() {
        return new ConnectionPoolLoggingListener();
    }

    /**
     * Returns a {@link ConnectionPoolListener} that logs the connection pool events with an alternative
     * {@link Ticker}.
     */
    static ConnectionPoolListener logging(Ticker ticker) {
        return new ConnectionPoolLoggingListener(ticker);
    }

    /**
     * Invoked when a new connection is open and ready to send a request.
     */
    void connectionOpen(SessionProtocol protocol,
                        InetSocketAddress remoteAddr,
                        InetSocketAddress localAddr,
                        AttributeMap attrs) throws Exception;

    /**
     * Invoked when a connection in the connection pool has been closed.
     */
    void connectionClosed(SessionProtocol protocol,
                          InetSocketAddress remoteAddr,
                          InetSocketAddress localAddr,
                          AttributeMap attrs) throws Exception;

    @Override
    default ConnectionPoolListener unwrap() {
        return this;
    }
}

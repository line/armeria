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

package com.linecorp.armeria.common;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeMap;

/**
 * Listens to the connection events.
 */
@UnstableApi
public interface ConnectionEventListener {
    /**
     * Invoked when a connection is opened.
     *
     * @param desiredProtocol the protocol that the client requested to use. null if it's server.
     * @param protocol the protocol that the connection is actually using.
     * @param remoteAddress the remote address of the connection.
     * @param localAddress the local address of the connection.
     * @param attrs the attributes of the connection.
     */
    void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                          SessionProtocol protocol,
                          InetSocketAddress remoteAddress,
                          InetSocketAddress localAddress,
                          AttributeMap attrs) throws Exception;

    /**
     * Invoked when a connection becomes active.
     *
     * @param protocol the protocol that the connection is using.
     * @param remoteAddress the remote address of the connection.
     * @param localAddress the local address of the connection.
     * @param attrs the attributes of the connection.
     * @param wasIdle whether the connection was idle.
     *                If {@code false}, it means the connection was just created.
     *                If {@code true}, it means the connection was idle and became active.
     */
    void connectionActive(SessionProtocol protocol,
                          InetSocketAddress remoteAddress,
                          InetSocketAddress localAddress,
                          AttributeMap attrs,
                          boolean wasIdle) throws Exception;

    /**
     * Invoked when a connection becomes idle.
     *
     * @param protocol the protocol that the connection is using.
     * @param remoteAddress the remote address of the connection.
     * @param localAddress the local address of the connection.
     * @param attrs the attributes of the connection.
     */
    void connectionIdle(SessionProtocol protocol,
                        InetSocketAddress remoteAddress,
                        InetSocketAddress localAddress,
                        AttributeMap attrs) throws Exception;

    /**
     * Invoked when a connection is closed.
     *
     * @param protocol the protocol that the connection is using.
     * @param remoteAddress the remote address of the connection.
     * @param localAddress the local address of the connection.
     * @param attrs the attributes of the connection.
     * @param wasIdle whether the connection was idle when is was closed.
     */
    void connectionClosed(SessionProtocol protocol,
                          InetSocketAddress remoteAddress,
                          InetSocketAddress localAddress,
                          AttributeMap attrs,
                          boolean wasIdle) throws Exception;
}

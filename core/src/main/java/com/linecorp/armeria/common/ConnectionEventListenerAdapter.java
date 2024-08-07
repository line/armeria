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

import io.netty.util.AttributeMap;

/**
 * A skeletal {@link ConnectionEventListener} implementation in order for a user to implement only the methods
 * what he or she really needs.
 */
public class ConnectionEventListenerAdapter implements ConnectionEventListener {
    static final ConnectionEventListenerAdapter NOOP = new ConnectionEventListenerAdapter();

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                                 SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {}

    @Override
    public void connectionActive(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean wasIdle) throws Exception {}

    @Override
    public void connectionIdle(SessionProtocol protocol,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress,
                               AttributeMap attrs) throws Exception {}

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs,
                                 boolean wasIdle) throws Exception {}
}

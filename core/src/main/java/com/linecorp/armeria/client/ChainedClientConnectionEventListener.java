/*
 * Copyright 2024 LINE Corporation
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
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeMap;

final class ChainedClientConnectionEventListener implements ClientConnectionEventListener {
    private final ClientConnectionEventListener first;
    private final ClientConnectionEventListener second;

    ChainedClientConnectionEventListener(ClientConnectionEventListener first,
                                         ClientConnectionEventListener second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public void connectionPending(SessionProtocol desiredProtocol, InetSocketAddress remoteAddress,
                                  InetSocketAddress localAddress, AttributeMap attrs) throws Exception {
        first.connectionPending(desiredProtocol, remoteAddress, localAddress, attrs);
        second.connectionPending(desiredProtocol, remoteAddress, localAddress, attrs);
    }

    @Override
    public void connectionFailed(SessionProtocol desiredProtocol, InetSocketAddress remoteAddress,
                                 @Nullable InetSocketAddress localAddress, AttributeMap attrs, Throwable cause,
                                 boolean wasPending) throws Exception {
        first.connectionFailed(desiredProtocol, remoteAddress, localAddress, attrs, cause, wasPending);
        second.connectionFailed(desiredProtocol, remoteAddress, localAddress, attrs, cause, wasPending);
    }

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol, SessionProtocol protocol,
                                 InetSocketAddress remoteAddress, InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {
        first.connectionOpened(desiredProtocol, protocol, remoteAddress, localAddress, attrs);
        second.connectionOpened(desiredProtocol, protocol, remoteAddress, localAddress, attrs);
    }

    @Override
    public void connectionActive(SessionProtocol protocol, InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress, AttributeMap attrs, boolean wasIdle)
            throws Exception {
        first.connectionActive(protocol, remoteAddress, localAddress, attrs, wasIdle);
        second.connectionActive(protocol, remoteAddress, localAddress, attrs, wasIdle);
    }

    @Override
    public void connectionIdle(SessionProtocol protocol, InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress, AttributeMap attrs) throws Exception {
        first.connectionIdle(protocol, remoteAddress, localAddress, attrs);
        second.connectionIdle(protocol, remoteAddress, localAddress, attrs);
    }

    @Override
    public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress, AttributeMap attrs, boolean wasIdle)
            throws Exception {
        first.connectionClosed(protocol, remoteAddress, localAddress, attrs, wasIdle);
        second.connectionClosed(protocol, remoteAddress, localAddress, attrs, wasIdle);
    }
}

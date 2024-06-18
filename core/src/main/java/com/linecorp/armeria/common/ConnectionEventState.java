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

import io.netty.util.AttributeKey;

/**
 * A key to identify a connection event.
 */
public final class ConnectionEventState {
    /**
     * The attribute key of the {@link ConnectionEventState}.
     */
    public static final AttributeKey<ConnectionEventState> CONNECTION_EVENT_STATE =
            AttributeKey.valueOf(ConnectionEventState.class, "CONNECTION_EVENT_STATE");
    /**
     * The remote address of the connection.
     */
    private final InetSocketAddress remoteAddress;
    /**
     * The local address of the connection.
     */
    private final InetSocketAddress localAddress;
    /**
     * The actual protocol of the connection.
     */
    @Nullable
    private SessionProtocol actualProtocol;

    /**
     * The desired protocol of the connection. This field is only used for client-side connection.
     */
    @Nullable
    private SessionProtocol desiredProtocol;

    /**
     * Whether the connection is active.
     */
    private boolean isActive;

    /**
     * Creates a new instance.
     */
    public ConnectionEventState(InetSocketAddress remoteAddress,
                                InetSocketAddress localAddress,
                                SessionProtocol desiredProtocol) {
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.desiredProtocol = desiredProtocol;
    }

    /**
     * Sets the actual protocol of the connection.
     */
    public ConnectionEventState setActualProtocol(SessionProtocol actualProtocol) {
        this.actualProtocol = actualProtocol;
        return this;
    }

    /**
     * Sets whether the connection is active.
     */
    public ConnectionEventState setActive(boolean isActive) {
        this.isActive = isActive;
        return this;
    }

    /**
     * Returns the remote address of the connection.
     */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the local address of the connection.
     */
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the protocol of the connection. This field can be null if the protocol is not determined yet.
     */
    @Nullable
    public SessionProtocol actualProtocol() {
        return actualProtocol;
    }

    /**
     * Returns the desired protocol of the connection. This field is only used for client-side connection.
     */
    @Nullable
    public SessionProtocol desiredProtocol() {
        return desiredProtocol;
    }

    /**
     * Returns whether the connection is active or not.
     */
    public boolean isActive() {
        return isActive;
    }
}

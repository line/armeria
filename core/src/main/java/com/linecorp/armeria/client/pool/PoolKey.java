/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client.pool;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * The default key of {@link KeyedChannelPool}. It consists of:
 * <ul>
 *   <li>the server's host name</li>
 *   <li>the server's port number</li>
 *   <li>the server's {@link SessionProtocol}</li>
 * </ul>
 */
public final class PoolKey {

    private final String host;
    private final int port;
    private final SessionProtocol sessionProtocol;

    /**
     * Creates a new key with the specified {@code host}, {@code port} and {@code sessionProtocol}.
     */
    public PoolKey(String host, int port, SessionProtocol sessionProtocol) {
        this.host = requireNonNull(host, "host");
        this.port = port;
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
    }

    /**
     * Returns the host name of the server associated with this key.
     */
    public String host() {
        return host;
    }

    /**
     * Returns the port number of the server associated with this key.
     */
    public int port() {
        return port;
    }

    /**
     * Returns the {@link SessionProtocol} of the server associated with this key.
     */
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PoolKey)) {
            return false;
        }

        final PoolKey that = (PoolKey) o;
        return host.equals(that.host) && port == that.port && sessionProtocol == that.sessionProtocol;
    }

    @Override
    public int hashCode() {
        return (host.hashCode() * 31 + port) * 31 + sessionProtocol.hashCode();
    }

    @Override
    public String toString() {
        return sessionProtocol.uriText() + "://" + host + ':' + port;
    }
}
